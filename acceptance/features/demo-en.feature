@demo @english @android @creates-temporary-recording
Feature: English key-feature demonstration of RecorderLong

  Scenario: Record a short session and show its local destination
    Given I begin a recorded demo
    And an Android device is connected through ADB
    And the configured Android app is installed
    When I grant Android permission "android.permission.RECORD_AUDIO"
    And I grant Android permission "android.permission.POST_NOTIFICATIONS"
    When I launch the configured Android app
    Then Android text "RecorderLong" is visible
    When I tap Android text "Stop" if it is enabled
    Given I remember existing Android entries under "/sdcard/Download/RecorderLong"
    When I narrate in "en-US" for at least 7 seconds:
      """
      RecorderLong runs microphone capture as an Android foreground service for long sessions, while keeping the output in the phone's Download slash RecorderLong folder.
      """
    And I start a temporary Android recording by tapping "Start"
    Then Android text containing "Recording part" is visible
    When I narrate in "en-US" for at least 8 seconds:
      """
      The live status identifies the current recording part and displays its destination. Auto-stop, silent notification, call silencing, and call rejection remain explicit settings below.
      """
    And I stop the temporary Android recording by tapping "Stop"
    Then Android text containing "Stopped" is visible
    And a new Android entry appears under "/sdcard/Download/RecorderLong"
    When I narrate in "en-US" for at least 6 seconds:
      """
      Stop finalizes the audio session. This demonstration now removes only the temporary session it created and leaves existing recordings untouched.
      """
    Then I delete only Android entries created during this scenario under "/sdcard/Download/RecorderLong"
    And I finish the recorded demo
