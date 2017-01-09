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
public class SimpleMessageListenerThreadImpl extends AbstractMessageListenerThread {

    private static Log log = LogFactory.getLog(SimpleMessageListenerThreadImpl.class);

    public SimpleMessageListenerThreadImpl(ClientUserInterface userInterface,
                                           Connection con, SharedClientData sharedData) {

        super(userInterface, con, sharedData);
    }

    @Override
    protected void loginEventAction(ChatPDU receivedPdu) {

        // Eventzaehler fuer Testzwecke erhoehen
        sharedClientData.eventCounter.getAndIncrement();

        try {
            handleUserListEvent(receivedPdu);
        } catch (Exception e) {
            ExceptionHandler.logException(e);
        }
    }

    @Override
    protected void chatMessageEventAction(ChatPDU receivedPdu) {

        log.debug("Chat-Message-Event-PDU von " + receivedPdu.getEventUserName() + " empfangen");

        // Eventzaehler fuer Testzwecke erhoehen
        sharedClientData.eventCounter.getAndIncrement();

        // Empfangene Chat-Nachricht an User Interface zur
        // Darstellung uebergeben
        userInterface.setMessageLine(receivedPdu.getEventUserName(), receivedPdu.getMessage());
    }

    @Override
    protected void logoutEventAction(ChatPDU receivedPdu) {

        // Eventzaehler fuer Testzwecke erhoehen
        sharedClientData.eventCounter.getAndIncrement();

        try {
            handleUserListEvent(receivedPdu);
        } catch (Exception e) {
            ExceptionHandler.logException(e);
        }
    }
}