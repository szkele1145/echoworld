package dev.worldreplay.fabric1182.client;

public enum RequestRecordingAction {
    REPLAY(0),
    DELETE(1);

    public final int value;
    RequestRecordingAction(int value) { this.value = value; }
}
