package edu.hm.dako.chat.server.worker;

import edu.hm.dako.chat.common.ChatPDU;
import edu.hm.dako.chat.common.ClientConversationStatus;
import edu.hm.dako.chat.common.ClientListEntry;
import edu.hm.dako.chat.common.ExceptionHandler;
import edu.hm.dako.chat.connection.Connection;
import edu.hm.dako.chat.connection.ConnectionTimeoutException;
import edu.hm.dako.chat.connection.EndOfFileException;
import edu.hm.dako.chat.server.ChatServerGuiInterface;
import edu.hm.dako.chat.server.SharedChatClientList;
import edu.hm.dako.chat.server.SharedServerCounter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.sound.midi.Soundbank;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abstrakte Klasse mit Basisfunktionalitaet fuer serverseitige Worker-Threads
 *
 * @author Peter Mandl
 */
public abstract class AbstractWorkerThread extends Thread {

    protected static final int NO_LOST_EVENT_CONFIRMS = 0;
    protected static final int NO_NUMBER_OF_RETRIES = 0;

    private static Log log = LogFactory.getLog(AbstractWorkerThread.class);

    // Verbindungs-Handle
    protected Connection connection;

    // Kennzeichen zum Beenden des Worker-Threads
    protected boolean finished = false;

    // Username des durch den Worker-Thread bedienten Clients
    protected String userName = null;

    // Client-Threadname
    protected String clientThreadName = null;

    // Startzeit fuer die Serverbearbeitungszeit
    protected long startTime;

    // Gemeinsam fuer alle Workerthreads verwaltete Liste aller eingeloggten
    // Clients
    protected final SharedChatClientList clients;

    // Referenzen auf globale Zaehler fuer Testausgaben
    protected AtomicInteger logoutCounter;
    protected AtomicInteger eventCounter;
    protected AtomicInteger confirmCounter;

    protected ChatServerGuiInterface serverGuiInterface;

    public AbstractWorkerThread(Connection con, SharedChatClientList clients,
                                SharedServerCounter counter, ChatServerGuiInterface serverGuiInterface) {
        this.connection = con;
        this.clients = clients;
        this.logoutCounter = counter.logoutCounter;
        this.eventCounter = counter.eventCounter;
        this.confirmCounter = counter.confirmCounter;
        this.serverGuiInterface = serverGuiInterface;
    }

    /**
     * Aktion fuer die Behandlung ankommender Login-Requests: Neuen Client anlegen
     * und alle Clients informieren
     *
     * @param receivedPdu Empfangene PDU
     */
    protected abstract void loginRequestAction(ChatPDU receivedPdu);

    /**
     * Aktion fuer die Behandlung ankommender Logout-Requests: Alle Clients
     * informieren, Response senden und Client loeschen
     *
     * @param receivedPdu Empfangene PDU
     */
    protected abstract void logoutRequestAction(ChatPDU receivedPdu);

    /**
     * Aktion fuer die Behandlung ankommender ChatMessage-Requests: Chat-Nachricht
     * an alle Clients weitermelden
     *
     * @param receivedPdu Empfangene PDU
     */
    protected abstract void chatMessageRequestAction(ChatPDU receivedPdu);

    /*
     _____  _    _ _____  _      _____ _____       _______ ______       _____ _____        _____ ____  _____  ______
    |  __ \| |  | |  __ \| |    |_   _/ ____|   /\|__   __|  ____|     / ____|  __ \      / ____/ __ \|  __ \|  ____|
    | |  | | |  | | |__) | |      | || |       /  \  | |  | |__       | |    | |__) |    | |   | |  | | |  | | |__
    | |  | | |  | |  ___/| |      | || |      / /\ \ | |  |  __|      | |    |  ___/     | |   | |  | | |  | |  __|
    | |__| | |__| | |    | |____ _| || |____ / ____ \| |  | |____     | |____| |         | |___| |__| | |__| | |____
    |_____/ \____/|_|    |______|_____\_____/_/    \_\_|  |______|     \_____|_|          \_____\____/|_____/|______|


     ____  _    _ _   _ _____  _      ______ _____      _    _ ______ _____  ______
    |  _ \| |  | | \ | |  __ \| |    |  ____|  __ \    | |  | |  ____|  __ \|  ____|
    | |_) | |  | |  \| | |  | | |    | |__  | |  | |   | |__| | |__  | |__) | |__
    |  _ <| |  | | . ` | |  | | |    |  __| | |  | |   |  __  |  __| |  _  /|  __|
    | |_) | |__| | |\  | |__| | |____| |____| |__| |   | |  | | |____| | \ \| |____
    |____/ \____/|_| \_|_____/|______|______|_____/    |_|  |_|______|_|  \_\______|

     */

    private void handleIncomingMessage() throws Exception {
        if (checkIfClientIsDeletable()) {
            return;
        }

        // Warten auf naechste Nachricht
        ChatPDU receivedPdu;

        // Nach einer Minute wird geprueft, ob Client noch eingeloggt ist
        final int RECEIVE_TIMEOUT = 60000;

        try {
            receivedPdu = (ChatPDU) connection.receive(RECEIVE_TIMEOUT);
            // Nachricht empfangen
            // Zeitmessung fuer Serverbearbeitungszeit starten
            startTime = System.nanoTime();

        } catch (ConnectionTimeoutException e) {

            // Wartezeit beim Empfang abgelaufen, pruefen, ob der Client
            // ueberhaupt noch etwas sendet
            log.debug("Timeout beim Empfangen, " + RECEIVE_TIMEOUT + " ms ohne Nachricht vom Client");

            if (clients.getClient(userName) != null) {
                if (clients.getClient(userName)
                        .getStatus() == ClientConversationStatus.UNREGISTERING) {
                    // Worker-Thread wartet auf eine Nachricht vom Client, aber es
                    // kommt nichts mehr an
                    log.error(
                            "Client ist im Zustand UNREGISTERING und bekommt aber keine Nachricht mehr");
                    // Zur Sicherheit eine Logout-Response-PDU an Client senden und
                    // dann Worker-Thread beenden
                    finished = true;
                }
            }
            return;

        } catch (EndOfFileException e) {
            log.debug("End of File beim Empfang, vermutlich Verbindungsabbau des Partners");
            finished = true;
            return;

        } catch (java.net.SocketException e) {
            log.debug("Verbindungsabbruch beim Empfang der naechsten Nachricht vom Client "
                    + getName());
            finished = true;
            return;

        } catch (Exception e) {
            log.debug("Empfang einer Nachricht fehlgeschlagen, Workerthread fuer User: " + userName);
            ExceptionHandler.logException(e);
            finished = true;
            return;
        }

        switchIncomingPdu(receivedPdu);
    }

    /**
     * Antwort-PDU fuer den initiierenden Client aufbauen und senden
     *
     * @param eventInitiatorClient Name des Clients
     */
    void sendLogoutResponse(String eventInitiatorClient) {
        sendLogoutResponse(eventInitiatorClient, null);
    }

    void sendLogoutResponse(String eventInitiatorClient, String transactionId) {
        ClientListEntry client = clients.getClient(eventInitiatorClient);

        if (client != null) {
            ChatPDU responsePdu = ChatPDU.createLogoutResponsePdu(
                    eventInitiatorClient,
                    client.getNumberOfSentEvents(),
                    NO_LOST_EVENT_CONFIRMS,
                    client.getNumberOfReceivedEventConfirms(),
                    NO_NUMBER_OF_RETRIES,
                    client.getNumberOfReceivedChatMessages(),
                    clientThreadName);
            responsePdu.setTransactionId(transactionId);
            responsePdu.setEventUserName(eventInitiatorClient);

            log.debug(eventInitiatorClient + ": SentEvents aus Clientliste: "
                    + client.getNumberOfSentEvents() + ": ReceivedConfirms aus Clientliste: "
                    + client.getNumberOfReceivedEventConfirms());
            try {
                clients.getClient(eventInitiatorClient).getConnection().send(responsePdu);

                //Information für debugging
                DirectionInfo.printPduDirection(responsePdu, DirectionInfo.Dir.S_TO_C, userName);

            } catch (Exception e) {
                log.debug("Senden einer Logout-Response-PDU an " + eventInitiatorClient
                        + " fehlgeschlagen");
                log.debug("Exception Message: " + e.getMessage());
                e.printStackTrace();
            }

            log.debug("Logout-Response-PDU an Client " + eventInitiatorClient + " gesendet");
        }
    }

    // Method which has the switch-case included
    protected abstract void switchIncomingPdu(ChatPDU receivedPdu);

    void loginResponse(ChatPDU receivedPdu) {
        // Login Response senden
        ChatPDU responsePdu = ChatPDU.createLoginResponsePdu(userName, receivedPdu);

        try {
            clients.getClient(userName).getConnection().send(responsePdu);
        } catch (Exception e) {
            log.debug("Senden einer Login-Response-PDU an " + userName + " fehlgeschlagen");
            log.debug("Exception Message: " + e.getMessage());
        }

        log.debug("Login-Response-PDU an Client " + userName + " gesendet");

        // Zustand des Clients aendern
        clients.changeClientStatus(userName, ClientConversationStatus.REGISTERED);
    }

    void loginRequestFromUnknownClient(ChatPDU receivedPdu) {
        ChatPDU pdu;// User bereits angemeldet, Fehlermeldung an Client senden,
        // Fehlercode an Client senden
        pdu = ChatPDU.createLoginErrorResponsePdu(receivedPdu, ChatPDU.LOGIN_ERROR);

        try {
            connection.send(pdu);
            log.debug("Login-Response-PDU an " + receivedPdu.getUserName()
                    + " mit Fehlercode " + ChatPDU.LOGIN_ERROR + " gesendet");
        } catch (Exception e) {
            log.debug("Senden einer Login-Response-PDU an " + receivedPdu.getUserName()
                    + " nicth moeglich");
            ExceptionHandler.logExceptionAndTerminate(e);
        }
    }

    /**
     * Senden eines Login-List-Update-Event an alle angemeldeten Clients
     *
     * @param pdu Zu sendende PDU
     */
    void sendLoginListUpdateEvent(ChatPDU pdu) {

        // Liste der eingeloggten bzw. sich einloggenden User ermitteln
        Vector<String> clientList = clients.getRegisteredClientNameList();

        log.debug("Aktuelle Clientliste, die an die Clients uebertragen wird: " + clientList);

        pdu.setClients(clientList);

        Vector<String> clientList2 = clients.getClientNameList();
        for (String s : new Vector<>(clientList2)) {

            log.debug("Fuer " + s + " wird Login- oder Logout-Event-PDU an alle aktiven Clients gesendet");

            ClientListEntry client = clients.getClient(s);
            try {
                if (client != null) {

                    //Information für debugging
                    DirectionInfo.printPduDirection(pdu, DirectionInfo.Dir.S_TO_C, userName);

                    client.getConnection().send(pdu);
                    log.debug(
                            "Login- oder Logout-Event-PDU an " + client.getUserName() + " gesendet");
                    clients.incrNumberOfSentChatEvents(client.getUserName());
                    eventCounter.getAndIncrement();
                    log.debug(userName + ": EventCounter bei Login/Logout erhoeht = "
                            + eventCounter.get() + ", ConfirmCounter = " + confirmCounter.get());
                }
            } catch (Exception e) {
                log.debug("Senden einer Login- oder Logout-Event-PDU an " + s + " nicht moeglich");
                ExceptionHandler.logException(e);
            }
        }
    }

    @Override
    public void run() {
        log.debug("ChatWorker-Thread erzeugt, Threadname: " + Thread.currentThread().getName());
        while (!finished && !Thread.currentThread().isInterrupted()) {
            try {
                // Warte auf naechste Nachricht des Clients und fuehre
                // entsprechende Aktion aus
                handleIncomingMessage();
            } catch (Exception e) {
                log.error("Exception waehrend der Nachrichtenverarbeitung");
                ExceptionHandler.logException(e);
            }
        }
        log.debug(Thread.currentThread().getName() + " beendet sich");
        closeConnection();
    }


    /**
     * Verbindung zu einem Client ordentlich abbauen
     */
    private void closeConnection() {

        log.debug("Schliessen der Chat-Connection zum " + userName);

        // Bereinigen der Clientliste falls erforderlich

        if (clients.existsClient(userName)) {
            log.debug("Close Connection fuer " + userName
                    + ", Laenge der Clientliste vor dem bedingungslosen Loeschen: "
                    + clients.size());

            clients.deleteClientWithoutCondition(userName);
            log.debug("Laenge der Clientliste nach dem bedingungslosen Loeschen von " + userName
                    + ": " + clients.size());
        }

        try {
            connection.close();
        } catch (Exception e) {
            log.debug("Exception bei close");
            // ExceptionHandler.logException(e);
        }
    }

    /**
     * Prueft, ob Clients aus der Clientliste geloescht werden koennen
     *
     * @return boolean, true: Client geloescht, false: Client nicht geloescht
     */
    private boolean checkIfClientIsDeletable() {

        ClientListEntry client;

        // Worker-Thread beenden, wenn sein Client schon abgemeldet ist
        if (userName != null) {
            client = clients.getClient(userName);
            if (client != null) {
                if (client.isFinished()) {
                    // Loesche den Client aus der Clientliste
                    // Ein Loeschen ist aber nur zulaessig, wenn der Client
                    // nicht mehr in einer anderen Warteliste ist
                    log.debug("Laenge der Clientliste vor dem Entfernen von " + userName + ": "
                            + clients.size());
                    if (clients.deleteClient(userName)) {
                        // Jetzt kann auch Worker-Thread beendet werden

                        log.debug("Laenge der Clientliste nach dem Entfernen von " + userName + ": "
                                + clients.size());
                        log.debug("Worker-Thread fuer " + userName + " zum Beenden vorgemerkt");
                        return true;
                    }
                }
            }
        }

        // Garbage Collection in der Clientliste durchfuehren
        Vector<String> deletedClients = clients.gcClientList();
        if (deletedClients.contains(userName)) {
            log.debug("Ueber Garbage Collector ermittelt: Laufender Worker-Thread fuer "
                    + userName + " kann beendet werden");
            finished = true;
            return true;
        }
        return false;
    }
}
