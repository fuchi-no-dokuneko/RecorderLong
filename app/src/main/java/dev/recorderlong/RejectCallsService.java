package dev.recorderlong;

import android.content.SharedPreferences;
import android.telecom.Call;
import android.telecom.CallScreeningService;

public class RejectCallsService extends CallScreeningService {
    @Override
    public void onScreenCall(Call.Details callDetails) {
        SharedPreferences preferences = getSharedPreferences(RecordingService.PREFS, MODE_PRIVATE);
        boolean rejectCalls = preferences.getBoolean(RecordingService.KEY_REJECT_CALLS_WHILE_RECORDING, false);
        boolean recordingRequested = preferences.getBoolean(RecordingService.KEY_RECORDING_REQUESTED, false);

        if (rejectCalls && recordingRequested) {
            CallResponse response = new CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipCallLog(false)
                    .setSkipNotification(false)
                    .build();
            respondToCall(callDetails, response);
        }
    }
}
