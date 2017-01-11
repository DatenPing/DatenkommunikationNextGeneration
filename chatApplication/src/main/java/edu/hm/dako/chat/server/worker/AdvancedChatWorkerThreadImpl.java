package edu.hm.dako.chat.server.worker;

import edu.hm.dako.chat.common.ChatPDU;
import edu.hm.dako.chat.common.ClientConversationStatus;
import edu.hm.dako.chat.common.ClientListEntry;
import edu.hm.dako.chat.common.ExceptionHandler;
import edu.hm.dako.chat.common.PduType;
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
 * @author B. Königsberg
 */
public class AdvancedChatWorkerThreadImpl extends AbstractWorkerThread {

    private static Log log = LogFactory.getLog(AdvancedChatWorkerThreadImpl.class);

    public AdvancedChatWorkerThreadImpl(Connection con, SharedChatClientList clients,
                                        SharedServerCounter counter, ChatServerGuiInterface serverGuiInterface) {

        super(con, clients, counter, serverGuiInterface);
    }

    @Override
    protected void loginRequestAction(ChatPDU loginRequestPDU) {
        //Information für debugging
        DirectionInfo.printPduDirection(loginRequestPDU, DirectionInfo.Dir.C_TO_S, userName);

        ChatPDU pdu;
        log.debug("Login-Request-PDU fuer " + loginRequestPDU.getUserName() + " empfangen");

        // Neuer Client moechte sich einloggen, Client in Client-Liste
        // eintragen
        if (!clients.existsClient(loginRequestPDU.getUserName())) {
            log.debug("User nicht in Clientliste: " + loginRequestPDU.getUserName());
            ClientListEntry client = new ClientListEntry(loginRequestPDU.getUserName(), connection);
            client.setLoginTime(System.nanoTime());
            clients.createClient(loginRequestPDU.getUserName(), client);
            clients.changeClientStatus(loginRequestPDU.getUserName(),
                    ClientConversationStatus.REGISTERING);
            log.debug("User " + loginRequestPDU.getUserName() + " nun in Clientliste");

            userName = loginRequestPDU.getUserName();
            clientThreadName = loginRequestPDU.getClientThreadName();
            Thread.currentThread().setName(loginRequestPDU.getUserName());
            log.debug("Laenge der Clientliste: " + clients.size());
            serverGuiInterface.incrNumberOfLoggedInClients();

            // Login-Event an alle Clients (auch an den gerade aktuell
            // anfragenden) senden
            // WICHTIG: 2. Waitlist MUSS vor dem senden erstellt werden
            clients.createWaitList(userName);

            pdu = ChatPDU.createLoginEventPdu(userName, loginRequestPDU);

            // Setze transaction vom request
            pdu.setTransactionId(loginRequestPDU.getTransactionId());

            sendLoginListUpdateEvent(pdu);

            // ehemals loginResponse(receivedPdu);
        } else {
            loginRequestFromUnknownClient(loginRequestPDU);
        }
    }

    /**
     * Der User der den Login-Confirm gesendet hat wird von der WaitList
     * des Initiators entfernt. Erst wenn die Waitlist leer ist, wird der
     * Response gesendet.
     *
     * @param loginConfirmPDU pdu
     */
    private void loginEventConfirmAction(ChatPDU loginConfirmPDU) {
        String eventUserName = loginConfirmPDU.getEventUserName();

        //Information für debugging
        DirectionInfo.printPduDirection(loginConfirmPDU, DirectionInfo.Dir.C_TO_S, userName);

        clients.deleteWaitListEntry(eventUserName, loginConfirmPDU.getUserName());

        tryCreateLoginResponsePDU(loginConfirmPDU);
    }

    /**
     * Wenn alle Confirms angekommen sind, schickt der letzte
     * den Response an den Urheber
     *
     * @param loginConfirmPDU pdu
     */
    private void tryCreateLoginResponsePDU(ChatPDU loginConfirmPDU) {
        String eventUserName = loginConfirmPDU.getEventUserName();

        // WICHTIG: 3. Wenn WaitList 0 -> Response an Initiator senden.
        if (clients.getWaitListSize(eventUserName) == 0) {

            ChatPDU loginResponse = new ChatPDU();
            loginResponse.setUserName(eventUserName);
            loginResponse.setPduType(PduType.LOGIN_RESPONSE);
            loginResponse.setTransactionId(loginConfirmPDU.getTransactionId());
            loginResponse.setClientStatus(ClientConversationStatus.REGISTERED);

            try {
                // WICHTIG: 4. Wir verwenden DIREKT die connection vom event Urheber
                clients.getClient(eventUserName)
                        .getConnection().send(loginResponse);

                //Information für debugging
                DirectionInfo.printPduDirection(loginResponse, DirectionInfo.Dir.S_TO_C, userName);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot send", e);
            }
        }

    }

    @Override
    protected void chatMessageRequestAction(ChatPDU messageRequestPDU) {

        //Information für debugging
        DirectionInfo.printPduDirection(messageRequestPDU, DirectionInfo.Dir.C_TO_S, userName);

        ClientListEntry client;
        clients.setRequestStartTime(messageRequestPDU.getUserName(), startTime);
        clients.incrNumberOfReceivedChatMessages(messageRequestPDU.getUserName());
        serverGuiInterface.incrNumberOfRequests();
        log.debug("Chat-Message-Request-PDU von " + messageRequestPDU.getUserName()
                + " mit Sequenznummer " + messageRequestPDU.getSequenceNumber() + " empfangen");

        if (!clients.existsClient(messageRequestPDU.getUserName())) {
            log.debug("User nicht in Clientliste: " + messageRequestPDU.getUserName());
        } else {
            clients.createWaitList(userName);

            sendMessageEventToAllClients(messageRequestPDU);
        }
    }

    /**
     * Die Nachricht des Initiators wird an alle Teilnehmer der WaitList gesendet
     *
     * @param messageRequestPDU pdu
     */
    private void sendMessageEventToAllClients(ChatPDU messageRequestPDU) {
        ClientListEntry client;// Liste der betroffenen Clients ermitteln
        Vector<String> sendList = clients.getClientNameList();
        ChatPDU pdu = ChatPDU.createChatMessageEventPdu(userName, messageRequestPDU);

        // Setze transaction vom reqeust
        pdu.setTransactionId(messageRequestPDU.getTransactionId());

        // WICHTIG: Setze SequenceNumber
        pdu.setSequenceNumber(messageRequestPDU.getSequenceNumber());

        // Event an Clients senden
        for (String s : new Vector<>(sendList)) {
            client = clients.getClient(s);
            try {
                if ((client != null)
                        && (client.getStatus() != ClientConversationStatus.UNREGISTERED)) {
                    pdu.setUserName(client.getUserName());

                    //Information für debugging
                    DirectionInfo.printPduDirection(pdu, DirectionInfo.Dir.S_TO_C, userName);
                    client.getConnection().send(pdu);

                    log.debug("Chat-Event-PDU an " + client.getUserName() + " gesendet");
                    clients.incrNumberOfSentChatEvents(client.getUserName());
                    eventCounter.getAndIncrement();
                    log.debug(userName + ": EventCounter erhoeht = " + eventCounter.get()
                            + ", Aktueller ConfirmCounter = " + confirmCounter.get()
                            + ", Anzahl gesendeter ChatMessages von dem Client = "
                            + messageRequestPDU.getSequenceNumber());
                }
            } catch (Exception e) {
                log.debug("Senden einer Chat-Event-PDU an " + client.getUserName()
                        + " nicht moeglich");
                ExceptionHandler.logException(e);
            }
        }
        log.debug("Aktuelle Laenge der Clientliste: " + clients.size());
    }

    /**
     * Wenn alle Message-Event-Confirms angekommen sind, schickt der letzte
     * den Response an den Urheber
     *
     * @param messageConfirmPDU pdu
     */
    private void messageEventConfirmAction(ChatPDU messageConfirmPDU) {
        DirectionInfo.printPduDirection(messageConfirmPDU, DirectionInfo.Dir.C_TO_S, userName);

        String eventUserName = messageConfirmPDU.getEventUserName();

        clients.deleteWaitListEntry(eventUserName, messageConfirmPDU.getUserName());

        ClientListEntry eventInitiatorClient;

        if (clients.getWaitListSize(eventUserName) == 0) {
            eventInitiatorClient = clients.getClient(eventUserName);

            if (eventInitiatorClient != null) {
                // Anzahl der eventConfirms erhöhen
                eventInitiatorClient.incrNumberOfReceivedEventConfirms();
                ChatPDU responsePdu = ChatPDU.createChatMessageResponsePdu(
                        eventUserName,
                        eventInitiatorClient.getNumberOfSentEvents(),
                        NO_LOST_EVENT_CONFIRMS,
                        eventInitiatorClient.getNumberOfReceivedEventConfirms(),
                        NO_NUMBER_OF_RETRIES,
                        eventInitiatorClient.getNumberOfReceivedChatMessages(),
                        messageConfirmPDU.getClientThreadName(),
                        System.nanoTime() - eventInitiatorClient.getStartTime());

                // Setze transaction vom request
                responsePdu.setTransactionId(messageConfirmPDU.getTransactionId());

                // Setze sequenzNumber
                responsePdu.setSequenceNumber(messageConfirmPDU.getSequenceNumber());

                if (responsePdu.getServerTime() / 1000000 > 100) {
                    log.debug(Thread.currentThread().getName()
                            + ", Benoetigte Serverzeit vor dem Senden der Response-Nachricht > 100 ms: "
                            + responsePdu.getServerTime() + " ns = "
                            + responsePdu.getServerTime() / 1000000 + " ms");
                }

                try {
                    eventInitiatorClient
                            .getConnection().send(responsePdu);

                    //Information für debugging
                    DirectionInfo.printPduDirection(responsePdu, DirectionInfo.Dir.S_TO_C, userName);
                    log.debug(
                            "Chat-Message-Response-PDU an " + messageConfirmPDU.getUserName() + " gesendet");
                } catch (Exception e) {
                    log.debug("Senden einer Chat-Message-Response-PDU an " + eventUserName + " nicht moeglich");
                    ExceptionHandler.logExceptionAndTerminate(e);
                }
            }
        }
    }


    @Override
    protected void logoutRequestAction(ChatPDU logoutRequestPdu) {

        //Information für debugging
        DirectionInfo.printPduDirection(logoutRequestPdu, DirectionInfo.Dir.C_TO_S, userName);

        ChatPDU pdu;
        logoutCounter.getAndIncrement();
        log.debug("Logout-Request von " + logoutRequestPdu.getUserName() + ", LogoutCount = "
                + logoutCounter.get());

        log.debug("Logout-Request-PDU von " + logoutRequestPdu.getUserName() + " empfangen");

        if (!clients.existsClient(userName)) {
            log.debug("User nicht in Clientliste: " + logoutRequestPdu.getUserName());
            System.out.println("Not in clientlist");
        } else {
            clients.createWaitList(userName);

            // Event an Client versenden
            pdu = ChatPDU.createLogoutEventPdu(userName, logoutRequestPdu);
            pdu.setTransactionId(logoutRequestPdu.getTransactionId());

            clients.changeClientStatus(logoutRequestPdu.getUserName(),
                    ClientConversationStatus.UNREGISTERING);

            sendLoginListUpdateEvent(pdu);

            serverGuiInterface.decrNumberOfLoggedInClients();

        }
    }

    /**
     * Wenn alle Logout-Confirms angekommen sind, schickt der letzte
     * den Response an den Urheber
     *
     * @param logoutEventPDU pdu
     */
    private void logoutEventConfirmAction(ChatPDU logoutEventPDU) {

        //Information für debugging
        DirectionInfo.printPduDirection(logoutEventPDU, DirectionInfo.Dir.C_TO_S, userName);

        String eventUserName = logoutEventPDU.getEventUserName();

        clients.deleteWaitListEntry(eventUserName, logoutEventPDU.getUserName());

        if (clients.getWaitListSize(eventUserName) == 0) {

            // Logout Response senden
            sendLogoutResponse(eventUserName, logoutEventPDU.getTransactionId());

            // Worker-Thread des Clients, der den Logout-Request gesendet
            // hat, auch gleich zum Beenden markieren
            clients.finish(eventUserName);
            log.debug("Laenge der Clientliste beim Vormerken zum Loeschen von " + eventUserName + ": " + clients.size());

            // Wenn client ist "still alive", setze auf UNREGISTERED
            clients.changeClientStatus(eventUserName, ClientConversationStatus.UNREGISTERED);
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

                case LOGIN_EVENT_CONFIRM:
                    //Login-Event-Confirm vom Client empfangen
                    loginEventConfirmAction(receivedPdu);
                    break;

                case CHAT_MESSAGE_REQUEST:
                    // Chat-Nachricht angekommen, an alle verteilen
                    chatMessageRequestAction(receivedPdu);
                    break;

                case CHAT_MESSAGE_EVENT_CONFIRM:
                    //Chat-Message-Event-Confirm vom Client empfangen
                    messageEventConfirmAction(receivedPdu);
                    break;

                case LOGOUT_REQUEST:
                    // Logout-Request vom Client empfangen
                    logoutRequestAction(receivedPdu);
                    break;

                case LOGOUT_EVENT_CONFIRM:
                    //Logout-Event-Confirm vom Client emfangen
                    logoutEventConfirmAction(receivedPdu);

                default:
                    log.debug("Falsche PDU empfangen von Client: " + receivedPdu.getUserName()
                            + ", PduType: " + receivedPdu.getPduType());
                    break;
            }
        } catch (Exception e) {
            System.out.println("Exception in switchIncomingPdu: " + e.getMessage());
            e.printStackTrace();

            log.error("Exception bei der Nachrichtenverarbeitung", e);
            ExceptionHandler.logExceptionAndTerminate(e);
        }
    }

}
