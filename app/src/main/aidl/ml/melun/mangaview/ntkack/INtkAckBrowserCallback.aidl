package ml.melun.mangaview.ntkack;

import ml.melun.mangaview.ntkack.NtkAckServiceHello;
import ml.melun.mangaview.ntkack.NtkAckFlightIdentity;
import ml.melun.mangaview.ntkack.NtkAckProof;
import ml.melun.mangaview.ntkack.NtkAckQuiescenceSeal;
import ml.melun.mangaview.ntkack.NtkAckSignature;
import ml.melun.mangaview.ntkack.NtkAckExactExchange;
import ml.melun.mangaview.ntkack.NtkAckFailure;

oneway interface INtkAckBrowserCallback {
    void onWarmReady(in NtkAckServiceHello hello);
    void onNetworkPrerequisitesReady(in NtkAckFlightIdentity identity);
    void onAckProved(in NtkAckProof proof);
    void onQuiesced(in NtkAckQuiescenceSeal seal);
    void onExactRequestSigned(in NtkAckSignature signature);
    void onExactRequestExecuted(in NtkAckExactExchange exchange);
    void onFailure(in NtkAckFailure failure);
}
