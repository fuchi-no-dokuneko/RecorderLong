@demo @cantonese @android @creates-temporary-recording
Feature: RecorderLong 粵語主要功能示範

  Scenario: 錄製短暫工作階段及顯示本機儲存位置
    Given I begin a recorded demo
    And an Android device is connected through ADB
    And the configured Android app is installed
    When I grant Android permission "android.permission.RECORD_AUDIO"
    And I grant Android permission "android.permission.POST_NOTIFICATIONS"
    When I launch the configured Android app
    Then Android text "RecorderLong" is visible
    When I tap Android text "Stop" if it is enabled
    Given I remember existing Android entries under "/sdcard/Download/RecorderLong"
    When I narrate in "yue-HK" for at least 7 seconds:
      """
      RecorderLong 會用 Android 前景服務錄低長時間咪高峰聲音，輸出會保留喺手機 Download 入面嘅 RecorderLong 資料夾。
      """
    And I start a temporary Android recording by tapping "Start"
    Then Android text containing "Recording part" is visible
    When I narrate in "yue-HK" for at least 8 seconds:
      """
      即時狀態會顯示目前錄音分段同儲存位置。下面亦有自動停止、靜音通知、來電靜音同拒接來電等清楚設定。
      """
    And I stop the temporary Android recording by tapping "Stop"
    Then Android text containing "Stopped" is visible
    And a new Android entry appears under "/sdcard/Download/RecorderLong"
    When I narrate in "yue-HK" for at least 6 seconds:
      """
      停止之後會完成音訊工作階段。示範而家只會刪除今次建立嘅暫存錄音，唔會郁現有檔案。
      """
    Then I delete only Android entries created during this scenario under "/sdcard/Download/RecorderLong"
    And I finish the recorded demo
