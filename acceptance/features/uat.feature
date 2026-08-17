@daily @uat @android
Feature: Daily acceptance of RecorderLong
  The daily laptop verifies permission status, persisted recording policy,
  system-setting shortcuts, and a real short microphone recording.

  Background:
    Given an Android device is connected through ADB
    And the configured Android app is installed
    When I grant Android permission "android.permission.RECORD_AUDIO"
    And I grant Android permission "android.permission.POST_NOTIFICATIONS"
    And I launch the configured Android app
    Then Android text "RecorderLong" is visible

  Scenario: Clamp and persist the automatic stop duration
    Given I remember Android field "Auto stop minutes (default 6 hours)" for restoration with button "Apply auto stop"
    When I replace the Android field labelled "Auto stop minutes (default 6 hours)" with "0"
    And I hide the Android keyboard
    And I tap Android text "Apply auto stop"
    Then the Android field labelled "Auto stop minutes (default 6 hours)" has value "1"
    When I launch the configured Android app
    Then the Android field labelled "Auto stop minutes (default 6 hours)" has value "1"
    When I restore Android field "Auto stop minutes (default 6 hours)"

  Scenario: Persist and restore recording notification and call controls
    Given I remember Android checkbox "Silent recording notification" for restoration
    And I remember Android checkbox "Silence calls while recording" for restoration
    And I remember Android checkbox "Reject calls while recording" for restoration
    When I set Android checkbox "Silent recording notification" to unchecked
    And I set Android checkbox "Silence calls while recording" to unchecked
    And I set Android checkbox "Reject calls while recording" to unchecked
    And I launch the configured Android app
    Then Android checkbox "Silent recording notification" is unchecked
    And Android checkbox "Silence calls while recording" is unchecked
    And Android checkbox "Reject calls while recording" is unchecked
    When I restore Android checkbox "Silent recording notification"
    And I restore Android checkbox "Silence calls while recording"
    And I restore Android checkbox "Reject calls while recording"

  Scenario: Open the Android permission and notification settings shortcuts
    When I tap Android text "App permission settings"
    And I press Android back
    Then the configured Android app is foreground
    When I tap Android text "Notification settings"
    And I press Android back
    Then the configured Android app is foreground
    And Android text "RecorderLong" is visible

  @creates-temporary-recording
  Scenario: Start and stop a short foreground recording
    When I tap Android text "Stop" if it is enabled
    Given I remember existing Android entries under "/sdcard/Download/RecorderLong"
    Then Android text containing "Permissions ready" is visible
    When I start a temporary Android recording by tapping "Start"
    Then Android text containing "Recording part" is visible
    When I wait for 3 seconds
    And I stop the temporary Android recording by tapping "Stop"
    Then Android text containing "Stopped" is visible
    And a new Android entry appears under "/sdcard/Download/RecorderLong"
    Then I delete only Android entries created during this scenario under "/sdcard/Download/RecorderLong"
