package com.feup.sdis.messages.requests.chord;

import com.feup.sdis.chord.Chord;
import com.feup.sdis.chord.SocketAddress;
import com.feup.sdis.messages.Status;
import com.feup.sdis.messages.requests.Request;
import com.feup.sdis.messages.responses.Response;
import com.feup.sdis.messages.responses.chord.GetPredecessorResponse;


public class GetPredecessorRequest extends Request {

    @Override
    public Response handle() {

        SocketAddress succ = Chord.chordInstance.getPredecessor();

        return new GetPredecessorResponse(Status.SUCCESS, succ);
    }

    @Override
    public SocketAddress getConnection() {

        return null;
    }

    @Override
    public String toString() {

        return "req: CHD_GET_PRED ";
    }
}

