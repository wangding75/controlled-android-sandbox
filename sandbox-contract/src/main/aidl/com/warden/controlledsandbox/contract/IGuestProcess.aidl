package com.warden.controlledsandbox.contract;
import android.os.Bundle;
interface IGuestProcess {
    Bundle prepareGuest(in Bundle request);
    Bundle invokeComponent(in Bundle request);
    Bundle runtimeStatus();
    void shutdown(String sessionId, long generation);
}
