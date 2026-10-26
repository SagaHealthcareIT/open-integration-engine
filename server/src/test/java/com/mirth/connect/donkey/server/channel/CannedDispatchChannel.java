package com.mirth.connect.donkey.server.channel;

import java.util.function.Function;

import com.mirth.connect.donkey.model.message.Message;
import com.mirth.connect.donkey.model.message.RawMessage;
import com.mirth.connect.donkey.model.message.Response;

/**
 * Test helper for the Jetty 12 migration Stage 0 characterization harness: a Channel whose
 * dispatchRawMessage (protected, so unmockable from other packages) returns a canned
 * DispatchResult, letting connector tests exercise their real receive path over HTTP without a
 * running engine.
 */
public class CannedDispatchChannel extends Channel {

    private Function<RawMessage, DispatchResult> dispatcher;

    public void setDispatcher(Function<RawMessage, DispatchResult> dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    protected DispatchResult dispatchRawMessage(RawMessage rawMessage, boolean batch) throws ChannelException {
        return dispatcher.apply(rawMessage);
    }

    /** Widens DispatchResult's protected constructor for tests outside the donkey packages. */
    public static DispatchResult createDispatchResult(long messageId, Message processedMessage, Response selectedResponse) {
        return new DispatchResult(messageId, processedMessage, selectedResponse, false, false) {};
    }
}
