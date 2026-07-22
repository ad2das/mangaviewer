package ml.melun.mangaview.ntkack;

import ml.melun.mangaview.ntkack.INtkAckBrowserCallback;
import ml.melun.mangaview.ntkack.NtkAckWarmRequest;
import ml.melun.mangaview.ntkack.NtkAckRequest;
import ml.melun.mangaview.ntkack.NtkAckFlightIdentity;
import ml.melun.mangaview.ntkack.NtkAckSignRequest;
import ml.melun.mangaview.ntkack.NtkAckClearRequest;

interface INtkAckBrowserService {
    void warm(in NtkAckWarmRequest request,
              in INtkAckBrowserCallback callback);

    void startAck(in NtkAckRequest request,
                  in INtkAckBrowserCallback callback);

    void quiesce(in NtkAckFlightIdentity identity,
                 in INtkAckBrowserCallback callback);

    void signExactRequest(in NtkAckSignRequest request,
                          in INtkAckBrowserCallback callback);

    void executeExactRequest(in NtkAckSignRequest request,
                             in INtkAckBrowserCallback callback);

    oneway void cancel(in NtkAckFlightIdentity identity,
                       int reasonCode);

    void clearStrictState(in NtkAckClearRequest request,
                          in INtkAckBrowserCallback callback);
}
