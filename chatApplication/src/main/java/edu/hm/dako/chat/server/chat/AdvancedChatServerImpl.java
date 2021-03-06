package edu.hm.dako.chat.server.chat;

import edu.hm.dako.chat.common.ClientListEntry;
import edu.hm.dako.chat.common.ExceptionHandler;
import edu.hm.dako.chat.connection.Connection;
import edu.hm.dako.chat.connection.ServerSocketInterface;
import edu.hm.dako.chat.server.ChatServerGuiInterface;
import edu.hm.dako.chat.server.SharedChatClientList;
import edu.hm.dako.chat.server.SharedServerCounter;
import edu.hm.dako.chat.server.worker.AdvancedChatWorkerThreadImpl;
import javafx.concurrent.Task;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p/>
 * Simple-Chat-Server-Implementierung
 *
 * @author Peter Mandl
 */
public class AdvancedChatServerImpl extends AbstractChatServer {

    private static Log log = LogFactory.getLog(AdvancedChatServerImpl.class);

    // Threadpool fuer Worker-Threads
    private final ExecutorService executorService;

    // Socket fuer den Listener, der alle Verbindungsaufbauwuensche der Clients
    // entgegennimmt
    private ServerSocketInterface socket;

    /**
     * Konstruktor
     *
     * @param executorService
     * @param socket
     * @param serverGuiInterface
     */
    public AdvancedChatServerImpl(ExecutorService executorService,
                                  ServerSocketInterface socket, ChatServerGuiInterface serverGuiInterface) {
        log.debug("SimpleChatServerImpl konstruiert");
        this.executorService = executorService;
        this.socket = socket;
        this.serverGuiInterface = serverGuiInterface;
        counter = new SharedServerCounter();
        counter.logoutCounter = new AtomicInteger(0);
        counter.eventCounter = new AtomicInteger(0);
        counter.confirmCounter = new AtomicInteger(0);
    }

    @Override
    public void start() {
        Task<Void> task = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                // Clientliste erzeugen
                clients = SharedChatClientList.getInstance();

                while (!Thread.currentThread().isInterrupted() && !socket.isClosed()) {
                    try {
                        // Auf ankommende Verbindungsaufbauwuensche warten
                        System.out.println(
                                "AdvancedChatServer wartet auf Verbindungsanfragen von Clients...");

                        Connection connection = socket.accept();
                        log.debug("Neuer Verbindungsaufbauwunsch empfangen");

                        // Neuen Workerthread starten
                        executorService.submit(new AdvancedChatWorkerThreadImpl(connection, clients,
                                counter, serverGuiInterface));
                    } catch (Exception e) {
                        if (socket.isClosed()) {
                            log.debug("Socket wurde geschlossen");
                        } else {
                            log.error(
                                    "Exception beim Entgegennehmen von Verbindungsaufbauwuenschen: " + e);
                            ExceptionHandler.logException(e);
                        }
                    }
                }
                return null;
            }
        };

        Thread th = new Thread(task);
        th.setDaemon(true);
        th.start();

    }

    @Override
    public void stop() throws Exception {

        // Alle Verbindungen zu aktiven Clients abbauen
        Vector<String> sendList = clients.getClientNameList();
        for (String s : new Vector<String>(sendList)) {
            ClientListEntry client = clients.getClient(s);
            try {
                if (client != null) {
                    client.getConnection().close();
                    log.error("Verbindung zu Client " + client.getUserName() + " geschlossen");
                }
            } catch (Exception e) {
                log.debug(
                        "Fehler beim Schliessen der Verbindung zu Client " + client.getUserName());
                ExceptionHandler.logException(e);
            }
        }

        // Loeschen der Userliste
        clients.deleteAll();
        Thread.currentThread().interrupt();
        socket.close();
        log.debug("Listen-Socket geschlossen");
        executorService.shutdown();
        log.debug("Threadpool freigegeben");

        System.out.println("AdvancedChatServer beendet sich");
    }
}