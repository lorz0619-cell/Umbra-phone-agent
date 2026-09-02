[CmdletBinding()]
param(
    [string]$DeviceId = "",
    [string]$AdbPath = "",
    [string]$OutputPath = "",
    [switch]$IncludeExisting,
    [switch]$VerboseTrace,
    [switch]$Demo
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$global:OutputEncoding = $utf8
& chcp.com 65001 | Out-Null

$script:lastStep = -1
$fieldLabels = @{
    task = "Task"
    mode = "Target screen"
    max_steps = "Max steps"
    max_action_failures = "Max action failures"
    max_planning_failures = "Max planning failures"
    screen = "Screen"
    package = "Foreground package"
    elements = "A11y elements"
    focused_text = "Focused text"
    tree_hash = "Tree hash"
    frame_id = "Frame id"
    frame_fresh = "Fresh frame"
    frame_source = "Frame source"
    action = "Action"
    app = "App"
    rationale = "Reasoning"
    expected_outcome = "Expected"
    coordinates_normalized = "Normalized point"
    coordinates_pixels = "Pixel point"
    target_box = "Target box"
    target_description = "Target"
    element_index = "Requested element"
    target_element_index = "Resolved element"
    target_label = "Target label"
    tap_strategy = "Tap strategy"
    swipe_normalized = "Normalized swipe"
    swipe_pixels = "Pixel swipe"
    duration_ms = "Duration (ms)"
    validated_duration_ms = "Validated duration"
    text = "Type text"
    text_length = "Text length"
    validated_text_length = "Validated length"
    success = "Verified"
    visual_change = "Visual change"
    package_changed = "Package changed"
    tree_changed = "Tree changed"
    stability_wait_ms = "Stability wait (ms)"
    stability_samples = "Stability samples"
    page_stable = "Page stable"
    last_frame_delta = "Last frame delta"
    verified = "Verified"
    consecutive_failures = "Consecutive failures"
    action_failures = "Action failures"
    planning_failures = "Planning failures"
    tap_target_attempts = "Target attempts"
    nearby_tap_attempts = "Nearby attempts"
    repeated_action = "Repeated action"
    no_visual_progress = "No visual progress"
    reflection_count = "Reflection count"
    subtask = "Subtask"
    subtask_key = "Subtask key"
    subtask_changed = "Subtask changed"
    subtask_reflections = "Subtask reflections"
    subtask_recovery_limit = "Subtask recovery limit"
    evidence = "Evidence"
    correction = "Correction"
    blocked_actions = "Blocked actions"
    error_class = "Error class"
    error_position = "Error position"
    raw_error_length = "Raw error length"
    diagnosis = "Diagnosis"
}

function Get-EventField {
    param(
        [Parameter(Mandatory)]$Event,
        [Parameter(Mandatory)][string]$Name
    )
    if ($null -eq $Event.fields) {
        return ""
    }
    $property = $Event.fields.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return ""
    }
    return [string]$property.Value
}

function Get-ShortText {
    param(
        [AllowEmptyString()][string]$Text,
        [int]$MaxLength = 140
    )
    if ([string]::IsNullOrWhiteSpace($Text) -or $Text.Length -le $MaxLength) {
        return $Text
    }
    return $Text.Substring(0, $MaxLength) + "...<len=" + $Text.Length + ">"
}

function Write-CompactStepHeader {
    param([int]$Step)
    if ($Step -gt 0 -and $Step -ne $script:lastStep) {
        $script:lastStep = $Step
        Write-Host ""
        Write-Host ("+---------------- STEP {0:D2} ----------------+" -f $Step) -ForegroundColor Cyan
    }
}

function Show-CompactUmbraEvent {
    param(
        [Parameter(Mandatory)]$Event,
        [Parameter(Mandatory)][string]$EventTime
    )
    $kind = [string]$Event.kind
    $level = [string]$Event.level
    $step = [int]$Event.step

    switch ($kind) {
        "TASK" {
            $script:lastStep = -1
            Write-Host ""
            Write-Host "+================ UMBRA AGENT RUN ================+" -ForegroundColor Cyan
            Write-Host ("| [{0}] Goal   {1}" -f $EventTime, (Get-ShortText (Get-EventField $Event "task") 220)) -ForegroundColor Cyan
            Write-Host ("|            Screen={0}  MaxSteps={1}  FailBudget={2}" -f
                (Get-EventField $Event "mode"),
                (Get-EventField $Event "max_steps"),
                (Get-EventField $Event "max_action_failures")) -ForegroundColor DarkGray
        }
        "PERCEPTION" {
            Write-Host ("| [{0}] [VIEW] pkg={1}  elements={2}  frame={3}/{4}" -f
                $EventTime,
                (Get-EventField $Event "package"),
                (Get-EventField $Event "elements"),
                (Get-EventField $Event "frame_source"),
                (Get-EventField $Event "frame_fresh")) -ForegroundColor DarkGray
        }
        "DECISION" {
            Write-CompactStepHeader $step
            $reason = Get-EventField $Event "rationale"
            if ([string]::IsNullOrWhiteSpace($reason)) { $reason = "(model supplied no concise reason)" }
            $expected = Get-EventField $Event "expected_outcome"
            if ([string]::IsNullOrWhiteSpace($expected)) { $expected = "(no expected outcome supplied)" }
            $action = Get-EventField $Event "action"
            $detail =
                switch ($action) {
                    "Launch" { "app=" + (Get-EventField $Event "app") }
                    "Tap" {
                        "target=" + (Get-EventField $Event "target_description") +
                            " normalized=" + (Get-EventField $Event "coordinates_normalized") +
                            " element=" + (Get-EventField $Event "element_index")
                    }
                    "Type" {
                        'text="' + (Get-ShortText (Get-EventField $Event "text") 120) +
                            '" length=' + (Get-EventField $Event "text_length")
                    }
                    "Swipe" { Get-EventField $Event "swipe_normalized" }
                    "Wait" { (Get-EventField $Event "duration_ms") + "ms" }
                    default { "" }
                }
            Write-Host ("| [{0}] [THINK] {1}" -f $EventTime, (Get-ShortText $reason 220)) -ForegroundColor Magenta
            Write-Host ("|            [DO]    {0} {1}" -f $action, $detail) -ForegroundColor White
            Write-Host ("|            [EXPECT] {0}" -f (Get-ShortText $expected 220)) -ForegroundColor DarkCyan
        }
        "EXECUTION" {
            $pixel = Get-EventField $Event "coordinates_pixels"
            $strategy = Get-EventField $Event "tap_strategy"
            $suffix = ""
            if (-not [string]::IsNullOrWhiteSpace($pixel)) {
                $suffix = " pixel=" + $pixel + " strategy=" + $strategy
            }
            $color = if ($level -eq "ERROR") { "Red" } else { "Blue" }
            Write-Host ("| [{0}] [EXEC] {1}{2}" -f $EventTime, (Get-ShortText ([string]$Event.message) 180), $suffix) -ForegroundColor $color
        }
        "VERIFICATION" {
            $success = Get-EventField $Event "success"
            $marker = if ($success -eq "true") { "[PASS]" } else { "[FAIL]" }
            $color = if ($success -eq "true") { "Green" } else { "Yellow" }
            Write-Host ("| [{0}] {1} {2}  visual={3} tree={4} package={5}" -f
                $EventTime,
                $marker,
                (Get-ShortText ([string]$Event.message) 180),
                (Get-EventField $Event "visual_change"),
                (Get-EventField $Event "tree_changed"),
                (Get-EventField $Event "package_changed")) -ForegroundColor $color
        }
        "REFLECTION" {
            Write-CompactStepHeader $step
            Write-Host ("| [{0}] [REFLECT] {1}" -f $EventTime, [string]$Event.message) -ForegroundColor Yellow
            $failureCause = Get-EventField $Event "failure_cause"
            if (-not [string]::IsNullOrWhiteSpace($failureCause)) {
                Write-Host ("|            Cause: {0}" -f (Get-ShortText $failureCause 240)) -ForegroundColor Yellow
                Write-Host ("|            Change: {0}" -f (Get-ShortText (Get-EventField $Event "strategy_change") 240)) -ForegroundColor Cyan
                Write-Host ("|            Next: {0}" -f (Get-ShortText (Get-EventField $Event "correction_action") 200)) -ForegroundColor Magenta
                Write-Host ("|            Expect: {0}" -f (Get-ShortText (Get-EventField $Event "expected_outcome") 220)) -ForegroundColor DarkCyan
            }
            else {
                Write-Host ("|            Evidence: {0}" -f (Get-ShortText (Get-EventField $Event "evidence") 240)) -ForegroundColor Yellow
                Write-Host ("|            Correction: {0}" -f (Get-ShortText (Get-EventField $Event "correction") 240)) -ForegroundColor DarkYellow
                Write-Host ("|            Blocked: {0}" -f (Get-EventField $Event "blocked_actions")) -ForegroundColor DarkGray
            }
        }
        "ROUTING" {
            if ($level -ne "INFO") {
                Write-Host ("| [{0}] [RETRY] {1}  actionFail={2} planFail={3}" -f
                    $EventTime,
                    (Get-ShortText ([string]$Event.message) 180),
                    (Get-EventField $Event "action_failures"),
                    (Get-EventField $Event "planning_failures")) -ForegroundColor Yellow
            }
        }
        "VALIDATION" {
            if ($level -eq "ERROR") {
                Write-Host ("| [{0}] [REJECT] {1}" -f $EventTime, (Get-ShortText ([string]$Event.message) 200)) -ForegroundColor Red
            }
        }
        "COMPLETE" {
            Write-Host ("| [{0}] [DONE] {1}" -f $EventTime, (Get-ShortText ([string]$Event.message) 260)) -ForegroundColor Green
            Write-Host "+----------------------------------------------------+" -ForegroundColor Green
        }
        "TAKEOVER" {
            Write-Host ("| [{0}] [HANDOFF] {1}" -f $EventTime, (Get-ShortText ([string]$Event.message) 260)) -ForegroundColor Magenta
            Write-Host "+----------------------------------------------------+" -ForegroundColor Magenta
        }
        "ERROR" {
            $diagnosis = Get-EventField $Event "diagnosis"
            Write-Host ("| [{0}] [ERROR] {1}: {2}" -f $EventTime, $Event.title, (Get-ShortText ([string]$Event.message) 260)) -ForegroundColor Red
            if (-not [string]::IsNullOrWhiteSpace($diagnosis)) {
                Write-Host ("|            Diagnosis: {0}; at={1}; rawLen={2}" -f
                    $diagnosis,
                    (Get-EventField $Event "error_position"),
                    (Get-EventField $Event "raw_error_length")) -ForegroundColor DarkRed
            }
            Write-Host "+----------------------------------------------------+" -ForegroundColor Red
        }
    }
}

function Write-EventField {
    param(
        [string]$Name,
        [AllowEmptyString()]
        [string]$Value
    )

    if ([string]::IsNullOrEmpty($Value)) {
        return
    }
    $label = if ($fieldLabels.ContainsKey($Name)) { $fieldLabels[$Name] } else { $Name }
    $displayValue = if ($Name -eq "text") { '"' + $Value + '"' } else { $Value }
    Write-Host ("|  {0,-22} {1}" -f ($label + ":"), $displayValue) -ForegroundColor Gray
}

function Show-UmbraEvent {
    param([Parameter(Mandatory)]$Event)

    $eventTime =
        try {
            [DateTimeOffset]::FromUnixTimeMilliseconds([long]$Event.time_ms).
                ToLocalTime().
                ToString("HH:mm:ss.fff")
        } catch {
            (Get-Date).ToString("HH:mm:ss.fff")
        }

    if (-not $VerboseTrace) {
        Show-CompactUmbraEvent -Event $Event -EventTime $eventTime
        return
    }

    $step = [int]$Event.step
    if ($step -gt 0 -and $step -ne $script:lastStep) {
        $script:lastStep = $step
        Write-Host ""
        Write-Host ("+-------------------- STEP {0:D2} --------------------+" -f $step) -ForegroundColor Cyan
    }

    $kind = [string]$Event.kind
    $level = [string]$Event.level
    $color =
        switch ($level) {
            "ERROR" { "Red" }
            "WARNING" { "Yellow" }
            default {
                switch ($kind) {
                    "TASK" { "Cyan" }
                    "DECISION" { "Magenta" }
                    "EXECUTION" { "Blue" }
                    "VERIFICATION" { "Green" }
                    "COMPLETE" { "Green" }
                    "TAKEOVER" { "Magenta" }
                    default { "DarkGray" }
                }
            }
        }
    $marker =
        switch ($kind) {
            "TASK" { "[RUN]" }
            "PHASE" { "[>>]" }
            "PERCEPTION" { "[SEE]" }
            "DECISION" { "[ACT]" }
            "VALIDATION" { "[CHK]" }
            "EXECUTION" { "[DO]" }
            "VERIFICATION" { "[OK?]" }
            "ROUTING" { "[NEXT]" }
            "REFLECTION" { "[REFLECT]" }
            "COMPLETE" { "[DONE]" }
            "TAKEOVER" { "[HANDOFF]" }
            "ERROR" { "[ERR]" }
            default { "[LOG]" }
        }

    if ($kind -eq "TASK") {
        Write-Host ""
        Write-Host "+================== UMBRA AGENT RUN ==================+" -ForegroundColor Cyan
    }

    Write-Host ("| [{0}] {1,-6} {2,-12} {3}" -f $eventTime, $marker, $Event.phase, $Event.title) -ForegroundColor $color
    if (-not [string]::IsNullOrWhiteSpace([string]$Event.message)) {
        Write-Host ("|  Result: {0}" -f $Event.message) -ForegroundColor $color
    }
    if ($null -ne $Event.fields) {
        foreach ($property in $Event.fields.PSObject.Properties) {
            Write-EventField -Name $property.Name -Value ([string]$property.Value)
        }
    }
    if ($kind -eq "COMPLETE" -or $kind -eq "TAKEOVER" -or $kind -eq "ERROR") {
        Write-Host "+------------------------------------------------------+" -ForegroundColor $color
    }
}

if ($Demo) {
    $now = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
    @(
        [pscustomobject]@{
            time_ms = $now
            kind = "TASK"
            level = "INFO"
            step = 0
            phase = "PERCEIVE"
            title = "Task started"
            message = ""
            fields = [pscustomobject]@{ task = "Open Settings and enter WLAN"; mode = "Virtual screen"; max_steps = "40" }
        },
        [pscustomobject]@{
            time_ms = $now + 20
            kind = "DECISION"
            level = "INFO"
            step = 1
            phase = "PLAN"
            title = "Model selected action"
            message = ""
            fields = [pscustomobject]@{ action = "Tap"; coordinates_normalized = "(512, 742)"; target_box = "[480,700][545,780]"; target_description = "WLAN entry"; rationale = "The WLAN entry is visible" }
        },
        [pscustomobject]@{
            time_ms = $now + 40
            kind = "VALIDATION"
            level = "INFO"
            step = 1
            phase = "VALIDATE"
            title = "Action validation passed"
            message = ""
            fields = [pscustomobject]@{ action = "Tap"; coordinates_pixels = "(553, 1781)"; target_element_index = "7"; target_label = "Search"; tap_strategy = "element_index" }
        },
        [pscustomobject]@{
            time_ms = $now + 60
            kind = "VERIFICATION"
            level = "INFO"
            step = 1
            phase = "VERIFY"
            title = "Post-action verification passed"
            message = "The page changed as expected"
            fields = [pscustomobject]@{ success = "true"; visual_change = "0.418"; tree_changed = "true"; stability_wait_ms = "1240"; stability_samples = "4"; page_stable = "true"; last_frame_delta = "0.001"; action_failures = "0"; planning_failures = "0"; tap_target_attempts = "0"; nearby_tap_attempts = "0" }
        }
    ) | ForEach-Object { Show-UmbraEvent $_ }
    exit 0
}

if ([string]::IsNullOrWhiteSpace($AdbPath)) {
    $sdkAdb =
        if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
            Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
        } else {
            "X:\Android\sdk\platform-tools\adb.exe"
        }
    if (Test-Path -LiteralPath $sdkAdb) {
        $AdbPath = $sdkAdb
    } else {
        $adbCommand = Get-Command adb -ErrorAction Stop
        $AdbPath = $adbCommand.Source
    }
}

if ([string]::IsNullOrWhiteSpace($DeviceId)) {
    $devices =
        @(
            & $AdbPath devices |
                Select-Object -Skip 1 |
                Where-Object { $_ -match "\sdevice$" } |
                ForEach-Object { ($_ -split "\s+")[0] }
        )
    if ($devices.Count -eq 0) {
        throw "No authorized ADB device found."
    }
    if ($devices.Count -gt 1) {
        throw "Multiple devices found. Use -DeviceId. Devices: $($devices -join ', ')"
    }
    $DeviceId = $devices[0]
}

Write-Host "Umbra Agent desktop monitor" -ForegroundColor Cyan
Write-Host "Device: $DeviceId" -ForegroundColor DarkGray
Write-Host "Waiting for a new task. Press Ctrl+C to stop." -ForegroundColor DarkGray
if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
    Write-Host "JSONL output: $OutputPath" -ForegroundColor DarkGray
}
Write-Host ""

$adbArguments = @("-s", $DeviceId, "logcat")
if (-not $IncludeExisting) {
    $adbArguments += @("-T", "1")
}
$adbArguments += @("-v", "raw", "UmbraAgent:I", "*:S")

& $AdbPath @adbArguments 2>&1 |
    ForEach-Object {
        $line = [string]$_
        $markerIndex = $line.IndexOf("UMBRA_EVENT ")
        if ($markerIndex -ge 0) {
            $payload = $line.Substring($markerIndex + "UMBRA_EVENT ".Length).Trim()
            try {
                $event = $payload | ConvertFrom-Json
                Show-UmbraEvent $event
                if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
                    Add-Content -LiteralPath $OutputPath -Value $payload -Encoding utf8
                }
            } catch {
                Write-Host "Could not parse event: $payload" -ForegroundColor Yellow
            }
        }
    }
