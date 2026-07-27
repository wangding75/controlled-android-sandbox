package com.warden.controlledsandbox.runtime.guest;

import android.os.Binder;

/** Marker Binder that must never be forwarded to the host ActivityManager. */
final class OrderedReceiverFinishToken extends Binder { }
