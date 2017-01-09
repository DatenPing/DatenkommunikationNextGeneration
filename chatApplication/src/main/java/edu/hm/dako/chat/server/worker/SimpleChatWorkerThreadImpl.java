package edu.hm.dako.chat.server.worker;

import edu.hm.dako.chat.common.ChatPDU;
import edu.hm.dako.chat.common.ClientConversationStatus;
import edu.hm.dako.chat.common.ClientListEntry;
import edu.hm.dako.chat.common.ExceptionHandler;
import edu.hm.dako.chat.connection.Connection;
import edu.hm.dako.chat.server.ChatServerGuiInterface;
import edu.hm.dako.chat.server.SharedChatClientList;
import edu.hm.dako.chat.server.SharedServerCounter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.Vector;

/**
 * Worker-Thread zur serverseitigen Bedienung einer Session mit einem Client.
 * Jedem Chat-Client wird serverseitig ein Worker-Thread zugeordnet.
 *
 * @author Mandl
 */
public class SimpleChatWorkerThreadImpl extends AbstractWorkerThread {

    private static Log log = LogFactory.getLog(SimpleChatWorkerThreadImpl.class);

    public SimpleChatWorkerThreadImpl(Connection con, SharedChatClientList clients,
                                      SharedServerCounter counter, ChatServerGuiInterface serverGuiInterface) {

        super(con, clients, counter, serverGuiInterface);
    }

    @Override
    protected void loginRequestAction(ChatPDU receivedPdu) {

        ChatPDU pdu;
        log.debug("Login-Request-PDU fuer " + receivedPdu.getUserName() + " empfangen");

        // Neuer Client moechte sich einloggen, Client in Client-Liste
        // eintragen
        if (!clients.existsClient(receivedPdu.getUserName())) {
            log.debug("User nicht in Clientliste: " + receivedPdu.getUserName());
            ClientListEntry client = new ClientListEntry(receivedPdu.getUserName(), connection);
            client.setLoginTime(System.nanoTime());
            clients.createClient(receivedPdu.getUserName(), client);
            clients.changeClientStatus(receivedPdu.getUserName(),
                    ClientConversationStatus.REGISTERING);
            log.debug("User " + receivedPdu.getUserName() + " nun in Clientliste");

            userName = receivedPdu.getUserName();
            clientThreadName = receivedPdu.getClientThreadName();
            Thread.currentThread().setName(receivedPdu.getUserName());
            log.debug("Laenge der Clientliste: " + clients.size());
            serverGuiInterface.incrNumberOfLoggedInClients();

            // Login-Event an alle Clients (auch an den gerade aktuell
            // anfragenden) senden
            pdu = ChatPDU.createLoginEventPdu(userName, receivedPdu);
            sendLoginListUpdateEvent(pdu);

            loginResponse(receivedPdu);
        } else {
            loginRequestFromUnknownClient(receivedPdu);
        }
    }

    @Override
    protected void chatMessageRequestAction(ChatPDU receivedPdu) {

        ClientListEntry client;
        clients.setRequestStartTime(receivedPdu.getUserName(), startTime);
        clients.incrNumberOfReceivedChatMessages(receivedPdu.getUserName());
        serverGuiInterface.incrNumberOfRequests();
        log.debug("Chat-Message-Request-PDU von " + receivedPdu.getUserName()
                + " mit Sequenznummer " + receivedPdu.getSequenceNumber() + " empfangen");

        if (!clients.existsClient(receivedPdu.getUserName())) {
            log.debug("User nicht in Clientliste: " + receivedPdu.getUserName());
        } else {
            // Liste der betroffenen Clients ermitteln
            Vector<String> sendList = clients.getClientNameList();
            ChatPDU pdu = ChatPDU.createChatMessageEventPdu(userName, receivedPdu);

            // Event an Clients senden
            for (String s : new Vector<>(sendList)) {
                client = clients.getClient(s);
                try {
                    if ((client != null)
                            && (client.getStatus() != ClientConversationStatus.UNREGISTERED)) {
                        pdu.setUserName(client.getUserName());
                        client.getConnection().send(pdu);
                        log.debug("Chat-Event-PDU an " + client.getUserName() + " gesendet");
                        clients.incrNumberOfSentChatEvents(client.getUserName());
                        eventCounter.getAndIncrement();
                        log.debug(userName + ": EventCounter erhoeht = " + eventCounter.get()
                                + ", Aktueller ConfirmCounter = " + confirmCounter.get()
                                + ", Anzahl gesendeter ChatMessages von dem Client = "
                                + receivedPdu.getSequenceNumber());
                    }
                } catch (Exception e) {
                    log.debug("Senden einer Chat-Event-PDU an " + client.getUserName()
                            + " nicht moeglich");
                    ExceptionHandler.logException(e);
                }
            }

            client = clients.getClient(receivedPdu.getUserName());
            if (client != null) {
                ChatPDU responsePdu = ChatPDU.createChatMessageResponsePdu(
                        receivedPdu.getUserName(), 0, 0, 0, 0,
                        client.getNumberOfReceivedChatMessages(), receivedPdu.getClientThreadName(),
                        (System.nanoTime() - client.getStartTime()));

                if (responsePdu.getServerTime() / 1000000 > 100) {
                    log.debug(Thread.currentThread().getName()
                            + ", Benoetigte Serverzeit vor dem Senden der Response-Nachricht > 100 ms: "
                            + responsePdu.getServerTime() + " ns = "
                            + responsePdu.getServerTime() / 1000000 + " ms");
                }

                try {
                    client.getConnection().send(responsePdu);
                    log.debug(
                            "Chat-Message-Response-PDU an " + receivedPdu.getUserName() + " gesendet");
                } catch (Exception e) {
                    log.debug("Senden einer Chat-Message-Response-PDU an " + client.getUserName()
                            + " nicht moeglich");
                    ExceptionHandler.logExceptionAndTerminate(e);
                }
            }
            log.debug("Aktuelle Laenge der Clientliste: " + clients.size());
        }
    }

    @Override
    protected void logoutRequestAction(ChatPDU receivedPdu) {

        ChatPDU pdu;
        logoutCounter.getAndIncrement();
        log.debug("Logout-Request von " + receivedPdu.getUserName() + ", LogoutCount = "
                + logoutCounter.get());

        log.debug("Logout-Request-PDU von " + receivedPdu.getUserName() + " empfangen");

        if (!clients.existsClient(userName)) {
            log.debug("User nicht in Clientliste: " + receivedPdu.getUserName());
        } else {

            // Event an Client versenden
            pdu = ChatPDU.createLogoutEventPdu(userName, receivedPdu);

            clients.changeClientStatus(receivedPdu.getUserName(),
                    ClientConversationStatus.UNREGISTERING);
            sendLoginListUpdateEvent(pdu);
            serverGuiInterface.decrNumberOfLoggedInClients();

            clients.changeClientStatus(receivedPdu.getUserName(),
                    ClientConversationStatus.UNREGISTERED);
            // Logout Response senden
            sendLogoutResponse(receivedPdu.getUserName());
            // Worker-Thread des Clients, der den Logout-Request gesendet
            // hat, auch gleich zum Beenden markieren
            clients.finish(receivedPdu.getUserName());
            log.debug("Laenge der Clientliste beim Vormerken zum Loeschen von "
                    + receivedPdu.getUserName() + ": " + clients.size());
        }
    }


    @Override
    protected void switchIncomingPdu(ChatPDU receivedPdu) {
        // Empfangene Nachricht bearbeiten
        try {
            switch (receivedPdu.getPduType()) {

                case LOGIN_REQUEST:
                    // Login-Request vom Client empfangen
                    loginRequestAction(receivedPdu);
                    break;

                case CHAT_MESSAGE_REQUEST:
                    // Chat-Nachricht angekommen, an alle verteilen
                    chatMessageRequestAction(receivedPdu);
                    break;

                case LOGOUT_REQUEST:
                    // Logout-Request vom Client empfangen
                    logoutRequestAction(receivedPdu);
                    break;

                default:
                    log.debug("Falsche PDU empfangen von Client: " + receivedPdu.getUserName()
                            + ", PduType: " + receivedPdu.getPduType());
                    break;
            }
        } catch (Exception e) {
            log.error("Exception bei der Nachrichtenverarbeitung", e);
            ExceptionHandler.logExceptionAndTerminate(e);
        }
    }
}
