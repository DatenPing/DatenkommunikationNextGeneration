package edu.hm.dako.chat.server.chat;

import edu.hm.dako.chat.server.ChatServerGuiInterface;
import edu.hm.dako.chat.server.ChatServerInterface;
import edu.hm.dako.chat.server.SharedChatClientList;
import edu.hm.dako.chat.server.SharedServerCounter;

/**
 * Gemeinsame Attribute fuer alle Implementierungen
 *
 * @author Peter Mandl
 */
public abstract class AbstractChatServer implements ChatServerInterface {

    // Gemeinsam fuer alle Workerthreads verwaltete Liste aller eingeloggten
    // Clients
    protected SharedChatClientList clients;

    // Zaehler fuer Test
    protected SharedServerCounter counter;

    // Referenz auf Server GUI fuer die Meldung von Ereignissen
    protected ChatServerGuiInterface serverGuiInterface;

}
