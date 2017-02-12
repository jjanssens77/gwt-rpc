package sharedchat.worker.client;

import com.colinalworth.gwt.websockets.client.ServerBuilder;
import com.colinalworth.gwt.worker.client.WorkerFactory;
import com.colinalworth.gwt.worker.client.worker.MessagePort;
import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.core.client.GWT;
import jsinterop.annotations.JsFunction;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsProperty;
import jsinterop.annotations.JsType;
import sharedchat.common.client.ChatPage;
import sharedchat.common.client.ChatWorker;
import sharedchat.common.shared.ChatClient;
import sharedchat.common.shared.ChatEvent;
import sharedchat.common.shared.ChatJoin;
import sharedchat.common.shared.ChatLeave;
import sharedchat.common.shared.ChatMessage;
import sharedchat.common.shared.ChatServer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shared/Service worker that will handle calls from the ui and make calls to the server.
 * It also keeps messages it has seen for later pages to request to catch up. In
 * theory at least, this should stash them away in localstorage or indexeddb, but
 * thats for another demo...
 */
public class AppWorker implements EntryPoint {

	//generated code
	public interface AppWorkerFactory extends WorkerFactory<ChatPage, ChatWorker> {}
	interface ChatServerBuilder extends ServerBuilder<ChatServer> {}


	//factories to handle communication
	ChatServerBuilder serverBuilder = GWT.create(ChatServerBuilder.class);
	AppWorkerFactory pageCommunicationFactory = GWT.create(AppWorkerFactory.class);

	//app state
	private String username;
	private List<ChatEvent> events = new ArrayList<>();
	private boolean connected;

	//pages that are probably still connected...
	private List<ChatPage> connectedPages = new ArrayList<>();

	private ChatServer serverConnection;

	@Override
	public void onModuleLoad() {
		serverBuilder.setPath("/chat");
		serverConnection = serverBuilder.start();

		self().setOnConnect(e -> {
			pageCommunicationFactory.wrapRemoteMessagePort(e.ports[0], new ChatWorker() {
				private ChatPage page;

				@Override
				public void login(String username) {
					if (AppWorker.this.username != null && !AppWorker.this.username.equals(username)) {
						//still logged in, signal logout first
						logout();

					}
					//set the username, clear messages
					AppWorker.this.username = username;
					AppWorker.this.events.clear();


					//tell the server we've joined
					serverConnection.login(username);

					//tell the pages we have a new username, clear messages
					broadcastToPages(page -> page.init(username, AppWorker.this.events));
				}

				@Override
				public void logout() {
					broadcastToPages(page -> page.init(null, new ArrayList<>()));
				}

				@Override
				public void send(String message) {
					serverConnection.say(message);
				}

				@Override
				public void setRemote(ChatPage chatPage) {
					page = chatPage;
					connectedPages.add(chatPage);
					// on connect, inform the page of the messages so far and our username if any
					page.init(username, events);
				}

				@Override
				public ChatPage getRemote() {
					return page;
				}
			});
		});

		serverConnection.setClient(new ChatClient() {
			@Override
			public void onOpen() {
				//inform page of connection established

				connected = true;
				broadcastToPages(ChatPage::connected);
			}

			@Override
			public void onClose() {
				//try reconnect...
				//inform page of lost connection

				connected = false;
				broadcastToPages(ChatPage::disconnected);

			}

			@Override
			public void onError(Throwable error) {

			}

			@Override
			public void say(String username, String message) {
				handleEvent(new ChatMessage(username, message));
			}

			@Override
			public void join(String username) {
				handleEvent(new ChatJoin(username));
			}

			@Override
			public void part(String username) {
				handleEvent(new ChatLeave(username));
			}
		});
	}

	private void handleEvent(ChatEvent event) {
		broadcastToPages(event::handle);
		events.add(event);
	}

	private void broadcastToPages(UnsafeConsumer<ChatPage> task) {
		for (Iterator<ChatPage> iterator = connectedPages.iterator(); iterator.hasNext(); ) {
			ChatPage connectedPage = iterator.next();
			try {
				task.acceptUnsafe(connectedPage);
			} catch (Exception e) {
				//disconnect
				iterator.remove();
			}
		}
	}
	interface UnsafeConsumer<T> extends Consumer<T> {
		@Override
		default void accept(T t) {
			try {
				acceptUnsafe(t);
			} catch (Exception e) {
				if (e instanceof RuntimeException) {
					throw (RuntimeException) e;
				}
				throw new RuntimeException(e);
			}
		}

		void acceptUnsafe(T t) throws Exception;
	}

	private native SharedWorkerGlobalScope self() /*-{
        return $wnd;
    }-*/;


	@JsType(isNative = true, namespace = JsPackage.GLOBAL)
	public interface SharedWorkerGlobalScope {
		@JsFunction
		public interface OnConnectCallback {
			void onConnect(Event event);

		}

		@JsProperty(name="onconnect")
		void setOnConnect(OnConnectCallback onconnect);

		@JsProperty(name="onconnect")
		OnConnectCallback getOnConnect();
	}
	@JsType(isNative = true, namespace = JsPackage.GLOBAL)
	public static class Event {
		MessagePort[] ports;
	}
}
