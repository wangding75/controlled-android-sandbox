package com.warden.controlledsandbox;

final class SandboxItem {
    final SandboxRecord record;
    final SandboxInstance instance;
    SandboxItem(SandboxRecord record, SandboxInstance instance) {
        this.record = record;
        this.instance = instance;
    }
}
