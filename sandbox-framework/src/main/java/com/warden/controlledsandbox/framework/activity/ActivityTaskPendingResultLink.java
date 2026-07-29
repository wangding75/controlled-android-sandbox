package com.warden.controlledsandbox.framework.activity;

record ActivityTaskPendingResultLink(
        String callerActivityToken,
        String resultWho,
        String registryKey,
        int requestCode,
        String intentSenderToken) { }
