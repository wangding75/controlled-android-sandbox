package com.warden.controlledsandbox.contract;
import android.os.Bundle;

/** Guest-to-Broker completion channel for one ordered Receiver delivery. */
interface IOrderedReceiverCompletion {
    Bundle complete(in Bundle result);
}
