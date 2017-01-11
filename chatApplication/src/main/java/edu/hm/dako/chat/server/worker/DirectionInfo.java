package edu.hm.dako.chat.server.worker;

import edu.hm.dako.chat.common.ChatPDU;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
/**
 * Diese Klasse soll das debugging vereifachen. Somit kann der Nachrichtenverlauf
 * kurz und kanpp eingesehen werden. Sie kann als log oder auf der Konsole ausgegeben
 * werden
 *
 * @author B. Königsberg
 */
public class DirectionInfo {

    private static Log log = LogFactory.getLog(DirectionInfo.class);

    /**
     *
     * @param pdu pdu
     * @param direction Richtungsanzeige
     * @param userName userName
     */
    public static void printPduDirection(ChatPDU pdu, Dir direction, String userName) {
        String dirString = direction == Dir.S_TO_C
                ? "-->   "
                : "   <--";

        log(String.format("[S%sC] [Type: %s] [TID: %s] [T: %s] [User: %s] [Send response to (EventUser): %s]",
                dirString,
                pdu.getPduType(),
                pdu.getTransactionId(),
                Thread.currentThread().getName(),
                userName,
                pdu.getEventUserName()));
    }

    /**
     * Formatierte Ausgabe für Client zu Server Übertragung
     *
     * @param sendingPdu pdu
     */
    public static void logSendRequest(ChatPDU sendingPdu) {
        log("[S <--- C]  Request: " + sendingPdu.getTransactionId());
    }

    /**
     * Formatierte Ausgabe für Server zu Client Übertragung
     *
     * @param receivedPdu pdu
     */
    public static void logReceivedResponse(ChatPDU receivedPdu) {
        log("[S ---> C] Response: " + receivedPdu.getTransactionId());
    }

    /**
     * InfoPrint für Log oder Konsole
     * @param message
     */
    private static void log(String message) {
        log.info(message);
        //System.out.println(message);
    }

    enum Dir {
        S_TO_C,
        C_TO_S
    }
}
