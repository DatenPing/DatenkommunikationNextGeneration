package edu.hm.dako.chat.client.messager;

import edu.hm.dako.chat.client.ClientUserInterface;
import edu.hm.dako.chat.client.SharedClientData;
import edu.hm.dako.chat.common.ChatPDU;
import edu.hm.dako.chat.common.ExceptionHandler;
import edu.hm.dako.chat.connection.Connection;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Thread wartet auf ankommende Nachrichten vom Server und bearbeitet diese.
 *
 * @author Peter Mandl
 */
public class AdvancedMessageListenerThreadImpl extends AbstractMessageListenerThread {

    private static Log log = LogFactory.getLog(AdvancedMessageListenerThreadImpl.class);

    public AdvancedMessageListenerThreadImpl(ClientUserInterface userInterface,
                                             Connection con, SharedClientData sharedData) {

        super(userInterface, con, sharedData);
    }

    @Override
    protected void loginEventAction(ChatPDU loginEventPDU) {

        // Eventzaehler fuer Testzwecke erhoehen
        sharedClientData.eventCounter.getAndIncrement();

        try {
            handleUserListEvent(loginEventPDU);

            // FIXME: 1. We use shared Data to respond
            ChatPDU loginEventConfirm = ChatPDU.createLoginEventConfirm(sharedClientData.userName, loginEventPDU);
            loginEventConfirm.setTransactionId(loginEventPDU.getTransactionId());

            connection.send(loginEventConfirm);
        } catch (Exception e) {
            e.printStackTrace();
            ExceptionHandler.logException(e);
        }
    }

    @Override
    protected void chatMessageEventAction(ChatPDU messageEventPDU) {

        log.debug("Chat-Message-Event-PDU von " + messageEventPDU.getEventUserName() + " empfangen");

        try {
            ChatPDU messageEventConfirm = ChatPDU.createChatMessageEventConfirm(sharedClientData.userName, messageEventPDU);
            messageEventConfirm.setTransactionId(messageEventPDU.getTransactionId());
            messageEventConfirm.setSequenceNumber(messageEventPDU.getSequenceNumber());

            connection.send(messageEventConfirm);
        } catch (Exception e) {
            e.printStackTrace();
            ExceptionHandler.logException(e);
        }

        // Eventzaehler fuer Testzwecke erhoehen
        sharedClientData.eventCounter.getAndIncrement();

        // Empfangene Chat-Nachricht an User Interface zur
        // Darstellung uebergeben
        userInterface.setMessageLine(messageEventPDU.getEventUserName(), messageEventPDU.getMessage());
    }

    @Override
    protected void logoutEventAction(ChatPDU receivedPdu) {

        // Eventzaehler fuer Testzwecke erhoehen
        sharedClientData.eventCounter.getAndIncrement();

        try {
            handleUserListEvent(receivedPdu);

            ChatPDU logoutEventConfirm = ChatPDU.createLogoutEventConfirm(sharedClientData.userName, receivedPdu);
            logoutEventConfirm.setTransactionId(receivedPdu.getTransactionId());

            connection.send(logoutEventConfirm);
        } catch (Exception e) {
            ExceptionHandler.logException(e);
        }
    }

}