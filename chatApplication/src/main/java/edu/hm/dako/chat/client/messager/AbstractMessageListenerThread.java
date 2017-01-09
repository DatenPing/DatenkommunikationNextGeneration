package edu.hm.dako.chat.client.messager;

import edu.hm.dako.chat.client.ClientUserInterface;
import edu.hm.dako.chat.client.SharedClientData;
import edu.hm.dako.chat.common.ChatPDU;
import edu.hm.dako.chat.common.ClientConversationStatus;
import edu.hm.dako.chat.common.ExceptionHandler;
import edu.hm.dako.chat.connection.Connection;
import edu.hm.dako.chat.server.worker.DirectionInfo;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Abstrakte Klasse mit Basisfunktionalitaet fuer clientseitige Message-Processing-Threads
 *
 * @author Peter Mandl
 */
public abstract class AbstractMessageListenerThread extends Thread {

    private static Log log = LogFactory.getLog(AbstractMessageListenerThread.class);

    // Kennzeichen zum Beenden der Bearbeitung
    protected boolean finished = false;

    // Verbindung zum Server
    protected Connection connection;

    // Schnittstelle zum User-Interface
    protected ClientUserInterface userInterface;

    // Gemeinsame Daten zwischen Client-Thread und Message-Processing-Thread
    protected SharedClientData sharedClientData;

    public AbstractMessageListenerThread(ClientUserInterface userInterface,
                                         Connection con, SharedClientData sharedData) {

        this.userInterface = userInterface;
        this.connection = con;
        this.sharedClientData = sharedData;
    }

    /**
     * Event vom Server zur Veraenderung der UserListe (eingeloggte Clients) verarbeiten
     *
     * @param receivedPdu Empfangene PDU
     */
    protected void handleUserListEvent(ChatPDU receivedPdu) {

        log.debug("Login- oder Logout-Event-PDU fuer "
                + receivedPdu.getUserName() + " empfangen");

        // Neue Userliste zur Darstellung an User Interface uebergeben
        log.debug("Empfangene Userliste: " + receivedPdu.getClients());
        userInterface.setUserList(receivedPdu.getClients());
    }

    /**
     * Chat-PDU empfangen
     *
     * @return Empfangene ChatPDU
     * @throws Exception
     */
    protected ChatPDU receive() throws Exception {
        try {
            ChatPDU receivedPdu = (ChatPDU) connection.receive();
            return receivedPdu;
        } catch (Exception e) {
            ExceptionHandler.logException(e);
        }
        return null;
    }

    /**
     * Aktion zur Behandlung ankommender ChatMessageResponses.
     *
     * @param receivedPdu Ankommende PDU
     */
    protected abstract void chatMessageEventAction(ChatPDU receivedPdu);

    /**
     * Aktion zur Behandlung ankommender Login-Events.
     *
     * @param receivedPdu Ankommende PDU
     */
    protected abstract void loginEventAction(ChatPDU receivedPdu);

    protected String createMessage(ChatPDU receivedPdu) {
        return String.format("[S/C: %d/%d TM: %d]",
                receivedPdu.getNumberOfSentEvents(),
                receivedPdu.getNumberOfReceivedConfirms(),
                receivedPdu.getNumberOfReceivedChatMessages());
    }

    /**
     * Aktion zur Behandlung ankommender Logout-Events.
     *
     * @param receivedPdu Ankommende PDU
     */
    protected abstract void logoutEventAction(ChatPDU receivedPdu);


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

    /**
     * Aktion zur Behandlung ankommender Login-Responsesd.
     *
     * @param receivedPdu Ankommende PDU
     */
    protected void loginResponseAction(ChatPDU receivedPdu) {

        if (receivedPdu.getErrorCode() == ChatPDU.LOGIN_ERROR) {

            // Login hat nicht funktioniert
            log.error("Login-Response-PDU fuer Client " + receivedPdu.getUserName()
                    + " mit Login-Error empfangen");
            userInterface.setErrorMessage(
                    "Chat-Server", "Anmelden beim Server nicht erfolgreich, Benutzer "
                            + receivedPdu.getUserName() + " vermutlich schon angemeldet",
                    receivedPdu.getErrorCode());
            sharedClientData.status = ClientConversationStatus.UNREGISTERED;

            // Verbindung wird gleich geschlossen
            try {
                connection.close();
            } catch (Exception e) {
                log.error("Error while closing", e);
            }

        } else {
            // Login hat funktioniert
            sharedClientData.status = ClientConversationStatus.REGISTERED;

            userInterface.loginComplete();

            Thread.currentThread().setName("Listener" + "-" + sharedClientData.userName);
            log.debug("Login-Response-PDU fuer Client " + receivedPdu.getUserName() + " empfangen");

            DirectionInfo.logReceivedResponse(receivedPdu);
        }
    }

    /**
     * Aktion zur Behandlung ankommender ChatMessageEvents.
     *
     * @param receivedPdu Ankommende PDU
     */
    protected void chatMessageResponseAction(ChatPDU receivedPdu) {

        log.debug("Sequenznummer der Chat-Response-PDU " + receivedPdu.getUserName() + ": "
                + receivedPdu.getSequenceNumber() + ", Messagecounter: "
                + sharedClientData.messageCounter.get());

        log.debug(Thread.currentThread().getName()
                + ", Benoetigte Serverzeit gleich nach Empfang der Response-Nachricht: "
                + receivedPdu.getServerTime() + " ns = " + receivedPdu.getServerTime() / 1000000
                + " ms");

        if (receivedPdu.getSequenceNumber() == sharedClientData.messageCounter.get()) {

            // Zuletzt gemessene Serverzeit fuer das Benchmarking
            // merken
            userInterface.setLastServerTime(receivedPdu.getServerTime());

            // Naechste Chat-Nachricht darf eingegeben werden
            userInterface.setLock(false);

            log.debug("Chat-Response-PDU fuer Client " + receivedPdu.getUserName() + " empfangen");


            userInterface.setMessageLine("[DEBUG]", createMessage(receivedPdu));

            DirectionInfo.logReceivedResponse(receivedPdu);

        } else {
            log.debug("Sequenznummer der Chat-Response-PDU " + receivedPdu.getUserName()
                    + " passt nicht: " + receivedPdu.getSequenceNumber() + "/"
                    + sharedClientData.messageCounter.get());
        }
    }

    /**
     * Aktion zur Behandlung ankommender Logout-Responses.
     *
     * @param receivedPdu Ankommende PDU
     */
    protected void logoutResponseAction(ChatPDU receivedPdu) {

        log.debug(sharedClientData.userName + " empfaengt Logout-Response-PDU fuer Client "
                + receivedPdu.getUserName());
        sharedClientData.status = ClientConversationStatus.UNREGISTERED;

        userInterface.setSessionStatisticsCounter(sharedClientData.eventCounter.longValue(),
                sharedClientData.confirmCounter.longValue(),
                receivedPdu.getNumberOfLostConfirms(),
                receivedPdu.getNumberOfRetries(),
                receivedPdu.getNumberOfReceivedChatMessages());

        log.debug("Vom Client gesendete Chat-Nachrichten:  " + sharedClientData.messageCounter.get());

        DirectionInfo.logReceivedResponse(receivedPdu);

        finished = true;
        userInterface.logoutComplete();
    }


    /**
     * Bearbeitung aller vom Server ankommenden Nachrichten
     */
    @Override
    public void run() {

        ChatPDU receivedPdu = null;

        log.debug("SimpleMessageListenerThread gestartet");

        while (!finished) {

            try {
                // Naechste ankommende Nachricht empfangen
                log.debug("Auf die naechste Nachricht vom Server warten");
                receivedPdu = receive();
                log.debug("Nach receive Aufruf, ankommende PDU mit PduType = "
                        + receivedPdu.getPduType());
            } catch (Exception e) {
                finished = true;
            }

            if (receivedPdu != null) {

                switch (sharedClientData.status) {

                    case REGISTERING:

                        switch (receivedPdu.getPduType()) {

                            case LOGIN_RESPONSE:
                                // Login-Bestaetigung vom Server angekommen
                                loginResponseAction(receivedPdu);

                                break;

                            case LOGIN_EVENT:
                                // Meldung vom Server, dass sich die Liste der
                                // angemeldeten User erweitert hat
                                loginEventAction(receivedPdu);

                                break;

                            case LOGOUT_EVENT:
                                // Meldung vom Server, dass sich die Liste der
                                // angemeldeten User veraendert hat
                                logoutEventAction(receivedPdu);

                                break;

                            case CHAT_MESSAGE_EVENT:
                                // Chat-Nachricht vom Server gesendet
                                chatMessageEventAction(receivedPdu);
                                break;

                            default:
                                log.debug("Ankommende PDU im Zustand " + sharedClientData.status + " wird verworfen");
                        }
                        break;

                    case REGISTERED:

                        switch (receivedPdu.getPduType()) {

                            case CHAT_MESSAGE_RESPONSE:

                                // Die eigene zuletzt gesendete Chat-Nachricht wird vom
                                // Server bestaetigt.
                                chatMessageResponseAction(receivedPdu);
                                break;

                            case CHAT_MESSAGE_EVENT:
                                // Chat-Nachricht vom Server gesendet
                                chatMessageEventAction(receivedPdu);
                                break;

                            case LOGIN_EVENT:
                                // Meldung vom Server, dass sich die Liste der
                                // angemeldeten User erweitert hat
                                loginEventAction(receivedPdu);

                                break;

                            case LOGOUT_EVENT:
                                // Meldung vom Server, dass sich die Liste der
                                // angemeldeten User veraendert hat
                                logoutEventAction(receivedPdu);

                                break;

                            default:
                                log.debug("Ankommende PDU im Zustand " + sharedClientData.status + " wird verworfen");
                        }
                        break;

                    case UNREGISTERING:

                        switch (receivedPdu.getPduType()) {

                            case CHAT_MESSAGE_EVENT:
                                // Chat-Nachricht vom Server gesendet
                                chatMessageEventAction(receivedPdu);
                                break;

                            case LOGOUT_RESPONSE:
                                // Bestaetigung des eigenen Logout
                                logoutResponseAction(receivedPdu);
                                break;

                            case LOGIN_EVENT:
                                // Meldung vom Server, dass sich die Liste der
                                // angemeldeten User erweitert hat
                                loginEventAction(receivedPdu);

                                break;

                            case LOGOUT_EVENT:
                                // Meldung vom Server, dass sich die Liste der
                                // angemeldeten User veraendert hat
                                logoutEventAction(receivedPdu);

                                break;

                            default:
                                log.debug("Ankommende PDU im Zustand " + sharedClientData.status + " wird verworfen");
                                break;
                        }
                        break;

                    case UNREGISTERED:
                        log.debug("Ankommende PDU im Zustand " + sharedClientData.status + " wird verworfen");

                        break;

                    default:
                        log.debug("Unzulaessiger Zustand " + sharedClientData.status);
                }
            }
        }

        // Verbindung noch schliessen
        try {
            connection.close();
        } catch (Exception e) {
            ExceptionHandler.logException(e);
        }
        log.debug("Ordnungsgemaesses Ende des SimpleMessageListener-Threads fuer User" + sharedClientData.userName + ", Status: " + sharedClientData.status);
    } // run
}
