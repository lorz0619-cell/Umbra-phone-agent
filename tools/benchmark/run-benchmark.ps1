[CmdletBinding()]
param(
    [string]$Suite = "benchmarks\suites\smoke.json",
    [string]$DeviceId = "",
    [string]$AdbPath = "",
    [string]$ResultsRoot = "benchmark-results",
    [string[]]$TaskId = @(),
    [ValidateSet("", "MAIN_SCREEN", "VIRTUAL_DISPLAY")]
    [string]$Mode = "",
    [ValidateRange(0, 50)]
    [int]$Repeat = 0,
    [switch]$List,
    [switch]$ValidateOnly,
    [switch]$NonInteractive,
    [switch]$Shuffle,
    [switch]$NoResetBeforeTask,
    [switch]$SingleRun,
    [ValidateRange(1, 50)]
    [int]$RandomSample = 0,
    [Alias("BuildAndInstall")]
    [switch]$BuildAndInstallBenchmark,
    [switch]$BuildAndInstallDebug,
    [switch]$FailOnTaskFailure
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$global:OutputEncoding = $utf8
& chcp.com 65001 | Out-Null

$packageName = "com.bluewhale.agent"
$receiverComponent = "$packageName/.benchmark.BenchmarkCommandReceiver"
$runAction = "$packageName.debug.RUN_BENCHMARK"
$stopAction = "$packageName.debug.STOP_BENCHMARK"
$eventMarker = "UMBRA_EVENT "
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))

function Get-PropertyValue {
    param($Object, [string]$Name, $Default)
    if ($null -eq $Object) { return $Default }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return $Default }
    return $property.Value
}

function Resolve-FromRepo {
    param([string]$Path)
    if ([IO.Path]::IsPathRooted($Path)) { return [IO.Path]::GetFullPath($Path) }
    return [IO.Path]::GetFullPath((Join-Path $repoRoot $Path))
}

function Get-AdbExecutable {
    param([string]$Requested)
    if (-not [string]::IsNullOrWhiteSpace($Requested)) {
        if (-not (Test-Path -LiteralPath $Requested)) { throw "ADB not found: $Requested" }
        return (Resolve-Path -LiteralPath $Requested).Path
    }
    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
        $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe")
    }
    $candidates += "X:\Android\sdk\platform-tools\adb.exe"
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -eq $command) { throw "ADB was not found. Set -AdbPath or ANDROID_HOME." }
    return $command.Source
}

function Invoke-Adb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $output = @(& $script:AdbExecutable @Arguments 2>&1)
    $code = $LASTEXITCODE
    if ($code -ne 0 -and -not $AllowFailure) {
        throw "ADB failed ($code): adb $($Arguments -join ' ')`n$($output -join "`n")"
    }
    return $output
}

function Get-EventField {
    param($Event, [string]$Name)
    $fields = Get-PropertyValue $Event "fields" $null
    if ($null -eq $fields) { return "" }
    return [string](Get-PropertyValue $fields $Name "")
}

function Get-BenchmarkEvents {
    param([string]$RunId)
    $lines = Invoke-Adb -Arguments @("-s", $script:SelectedDevice, "logcat", "-d", "-v", "raw", "UmbraAgent:I", "*:S") -AllowFailure
    $events = New-Object System.Collections.Generic.List[object]
    foreach ($rawLine in $lines) {
        $line = [string]$rawLine
        $index = $line.IndexOf($eventMarker)
        if ($index -lt 0) { continue }
        $payload = $line.Substring($index + $eventMarker.Length).Trim()
        try {
            $event = $payload | ConvertFrom-Json
            if ((Get-EventField $event "benchmark_run_id") -eq $RunId) {
                $events.Add($event)
            }
        } catch {
            # A malformed or truncated unrelated Logcat line is not a benchmark event.
        }
    }
    return $events.ToArray()
}

function Show-NewEvents {
    param([object[]]$Events, [int]$StartIndex)
    for ($i = $StartIndex; $i -lt $Events.Count; $i++) {
        $event = $Events[$i]
        $step = [int](Get-PropertyValue $event "step" 0)
        $kind = [string](Get-PropertyValue $event "kind" "")
        switch ($kind) {
            "DECISION" {
                Write-Host ("  step {0:D2}  DO       {1}" -f $step, (Get-EventField $event "action")) -ForegroundColor Cyan
            }
            "VERIFICATION" {
                $passed = (Get-EventField $event "success") -eq "true"
                $label = if ($passed) { "PASS" } else { "FAIL" }
                $color = if ($passed) { "Green" } else { "Yellow" }
                Write-Host ("  step {0:D2}  VERIFY   {1}" -f $step, $label) -ForegroundColor $color
            }
            "REFLECTION" {
                Write-Host ("  step {0:D2}  REFLECT  {1}" -f $step, [string](Get-PropertyValue $event "message" "")) -ForegroundColor Yellow
            }
        }
    }
}

function Test-PackageInstalled {
    param([string]$AndroidPackage)
    $output = Invoke-Adb -Arguments @("-s", $script:SelectedDevice, "shell", "pm", "path", $AndroidPackage) -AllowFailure
    return (($output -join "`n") -match "package:")
}

function Reset-BenchmarkState {
    param([string[]]$Packages)
    $null = Invoke-Adb -Arguments @("-s", $script:SelectedDevice, "shell", "input", "keyevent", "3") -AllowFailure
    foreach ($package in $Packages) {
        $null = Invoke-Adb -Arguments @("-s", $script:SelectedDevice, "shell", "am", "force-stop", $package) -AllowFailure
    }
    Start-Sleep -Milliseconds 800
}

function Add-Check {
    param(
        [System.Collections.Generic.List[object]]$Checks,
        [string]$Name,
        [bool]$Passed,
        [string]$Expected,
        [string]$Actual
    )
    $Checks.Add([pscustomobject]@{
        name = $Name
        passed = $Passed
        expected = $Expected
        actual = $Actual
    })
}

function Write-Utf8Json {
    param([string]$Path, $Value)
    $json = $Value | ConvertTo-Json -Depth 30
    [IO.File]::WriteAllText($Path, $json, $utf8)
}

function Write-EventJsonl {
    param([string]$Path, [object[]]$Events)
    $lines = @($Events | ForEach-Object { $_ | ConvertTo-Json -Depth 20 -Compress })
    [IO.File]::WriteAllLines($Path, $lines, $utf8)
}

function Validate-Suite {
    param($SuiteObject)
    if ([int](Get-PropertyValue $SuiteObject "schema_version" 0) -ne 1) {
        throw "Unsupported suite schema_version. Expected 1."
    }
    if ([string]::IsNullOrWhiteSpace([string](Get-PropertyValue $SuiteObject "name" ""))) {
        throw "Suite name is required."
    }
    $ids = @{}
    $tasks = @(Get-PropertyValue $SuiteObject "tasks" @())
    if ($tasks.Count -eq 0) { throw "Suite must contain at least one task." }
    foreach ($task in $tasks) {
        $id = [string](Get-PropertyValue $task "id" "")
        $instruction = [string](Get-PropertyValue $task "instruction" "")
        if ($id -notmatch "^[a-z0-9][a-z0-9._-]{0,79}$") { throw "Invalid task id: $id" }
        if ($ids.ContainsKey($id)) { throw "Duplicate task id: $id" }
        $ids[$id] = $true
        if ([string]::IsNullOrWhiteSpace($instruction) -or $instruction.Length -gt 4000) {
            throw "Task $id instruction must contain 1..4000 characters."
        }
        $taskMode = [string](Get-PropertyValue $task "mode" "")
        if ($taskMode -and $taskMode -notin @("MAIN_SCREEN", "VIRTUAL_DISPLAY")) {
            throw "Task $id has invalid mode: $taskMode"
        }
        $oracle = Get-PropertyValue $task "oracle" $null
        $packageRegex = [string](Get-PropertyValue $oracle "package_regex" "")
        if ($packageRegex) {
            try { $null = [regex]::new($packageRegex) } catch { throw "Task $id has invalid package_regex." }
        }
    }
    return $tasks
}

$suitePath = Resolve-FromRepo $Suite
if (-not (Test-Path -LiteralPath $suitePath)) { throw "Suite not found: $suitePath" }
$suiteObject = Get-Content -Raw -Encoding UTF8 -LiteralPath $suitePath | ConvertFrom-Json
$allTasks = @(Validate-Suite $suiteObject)
$defaults = Get-PropertyValue $suiteObject "defaults" $null

if ($List) {
    $allTasks | ForEach-Object {
        [pscustomobject]@{
            Id = $_.id
            Enabled = [bool](Get-PropertyValue $_ "enabled" $true)
            Mode = [string](Get-PropertyValue $_ "mode" (Get-PropertyValue $defaults "mode" "MAIN_SCREEN"))
            Category = [string](Get-PropertyValue $_ "category" "")
            Instruction = $_.instruction
        }
    } | Format-Table -AutoSize
    exit 0
}

if ($ValidateOnly) {
    Write-Host "Umbra benchmark suite validated" -ForegroundColor Green
    Write-Host ("Suite: {0} ({1} tasks)" -f $suiteObject.name, $allTasks.Count)
    exit 0
}

$selectedTasks = @($allTasks | Where-Object {
    $enabled = [bool](Get-PropertyValue $_ "enabled" $true)
    $selected = $TaskId.Count -eq 0 -or $TaskId -contains [string]$_.id
    $enabled -and $selected
})
if ($selectedTasks.Count -eq 0) { throw "No enabled tasks matched the selection." }
if ($RandomSample -gt 0) {
    $sampleSize = [Math]::Min($RandomSample, $selectedTasks.Count)
    $selectedTasks = @($selectedTasks | Get-Random -Count $sampleSize)
}
if ($Shuffle) {
    $selectedTasks = @($selectedTasks | Sort-Object { Get-Random })
}

Write-Host "Umbra benchmark suite validated" -ForegroundColor Green
Write-Host ("Suite: {0} ({1} selected tasks)" -f $suiteObject.name, $selectedTasks.Count)

$script:AdbExecutable = Get-AdbExecutable $AdbPath
if ($BuildAndInstallBenchmark -and $BuildAndInstallDebug) {
    throw "Choose either -BuildAndInstallBenchmark or -BuildAndInstallDebug, not both."
}
if ($BuildAndInstallBenchmark -or $BuildAndInstallDebug) {
    $installTask = if ($BuildAndInstallBenchmark) { ":app:installBenchmark" } else { ":app:installDebug" }
    Push-Location (Join-Path $repoRoot "android")
    try {
        & .\gradlew.bat $installTask
        if ($LASTEXITCODE -ne 0) {
            throw "Benchmark installation failed. A signing mismatch may require a matching keystore; this script never uninstalls the app automatically."
        }
    } finally {
        Pop-Location
    }
}

if ([string]::IsNullOrWhiteSpace($DeviceId)) {
    $devices = @(
        & $script:AdbExecutable devices |
            Select-Object -Skip 1 |
            Where-Object { $_ -match "\sdevice$" } |
            ForEach-Object { ($_ -split "\s+")[0] }
    )
    if ($devices.Count -eq 0) { throw "No authorized ADB device found." }
    if ($devices.Count -gt 1) { throw "Multiple devices found; use -DeviceId. Devices: $($devices -join ', ')" }
    $DeviceId = $devices[0]
}
$script:SelectedDevice = $DeviceId

$packageDump = Invoke-Adb -Arguments @("-s", $DeviceId, "shell", "dumpsys", "package", $packageName) -AllowFailure
if (($packageDump -join "`n") -notmatch "BenchmarkCommandReceiver") {
    throw "The installed app does not contain the benchmark bridge. Build/install it with -BuildAndInstallBenchmark."
}
$accessibility = (Invoke-Adb -Arguments @("-s", $DeviceId, "shell", "settings", "get", "secure", "enabled_accessibility_services") -AllowFailure) -join "`n"
if ($accessibility -notmatch [regex]::Escape($packageName)) {
    throw "Umbra accessibility service is not enabled on the device."
}

$resultRootPath = Resolve-FromRepo $ResultsRoot
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$sessionPath = Join-Path $resultRootPath ("{0}-{1}" -f $suiteObject.name, $timestamp)
$runsPath = Join-Path $sessionPath "runs"
$null = New-Item -ItemType Directory -Path $runsPath -Force
Copy-Item -LiteralPath $suitePath -Destination (Join-Path $sessionPath "suite.json")

$results = New-Object System.Collections.Generic.List[object]
Write-Host ("Device: {0}" -f $DeviceId) -ForegroundColor DarkGray
Write-Host ("Results: {0}" -f $sessionPath) -ForegroundColor DarkGray

foreach ($task in $selectedTasks) {
    $taskMode = if ($Mode) { $Mode } else { [string](Get-PropertyValue $task "mode" (Get-PropertyValue $defaults "mode" "MAIN_SCREEN")) }
    $maxSteps = [int](Get-PropertyValue $task "max_steps" (Get-PropertyValue $defaults "max_steps" 40))
    $timeoutSeconds = [int](Get-PropertyValue $task "timeout_seconds" (Get-PropertyValue $defaults "timeout_seconds" 300))
    $repetitions = if ($SingleRun) { 1 } elseif ($Repeat -gt 0) { $Repeat } else { [int](Get-PropertyValue $task "repetitions" (Get-PropertyValue $defaults "repetitions" 1)) }
    $requiredPackages = @(Get-PropertyValue $task "required_packages" @())
    $missingPackages = @($requiredPackages | Where-Object { -not (Test-PackageInstalled ([string]$_)) })
    if (-not $NoResetBeforeTask) {
        Reset-BenchmarkState -Packages $requiredPackages
    }

    for ($iteration = 1; $iteration -le $repetitions; $iteration++) {
        $runId = "{0}-r{1}-{2}" -f $task.id, $iteration, [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $runPath = Join-Path $runsPath $runId
        $null = New-Item -ItemType Directory -Path $runPath -Force
        Write-Host ""
        Write-Host ("[{0}/{1}] {2}  mode={3}" -f $iteration, $repetitions, $task.id, $taskMode) -ForegroundColor Magenta
        Write-Host ("  {0}" -f $task.instruction)

        if ($missingPackages.Count -gt 0) {
            Write-Host ("  SKIP missing package(s): {0}" -f ($missingPackages -join ", ")) -ForegroundColor Yellow
            $result = [pscustomobject]@{
                task_id = [string]$task.id; iteration = $iteration; mode = $taskMode; status = "SKIP"
                auto_pass = $false; manual_status = "NOT_RUN"; terminal_phase = ""; steps = 0
                reflections = 0; failed_verifications = 0; duration_seconds = 0
                actions = @(); system_tools = @(); package = ""; reason = "Missing packages: $($missingPackages -join ', ')"
                checks = @(); run_id = $runId
            }
            $results.Add($result)
            Write-Utf8Json (Join-Path $runPath "result.json") $result
            continue
        }

        $null = Invoke-Adb -Arguments @("-s", $DeviceId, "logcat", "-c")
        $encodedTask = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]$task.instruction))
        $broadcastOutput = Invoke-Adb -Arguments @(
            "-s", $DeviceId, "shell", "am", "broadcast",
            "-a", $runAction, "-n", $receiverComponent,
            "--es", "task_base64", $encodedTask,
            "--es", "run_id", $runId,
            "--es", "mode", $taskMode,
            "--ei", "max_steps", $maxSteps.ToString()
        )
        if (($broadcastOutput -join "`n") -notmatch "Broadcast completed") {
            throw "Benchmark broadcast was not accepted: $($broadcastOutput -join ' ')"
        }

        $started = Get-Date
        $deadline = $started.AddSeconds($timeoutSeconds)
        $events = @()
        $seenCount = 0
        $terminal = $null
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 1200
            $events = @(Get-BenchmarkEvents $runId)
            if ($events.Count -gt $seenCount) {
                Show-NewEvents $events $seenCount
                $seenCount = $events.Count
            }
            $terminal = @($events | Where-Object {
                ([string]$_.kind -in @("COMPLETE", "TAKEOVER", "ERROR")) -and
                ([string]$_.phase -in @("COMPLETE", "TAKEOVER", "FAILED"))
            } | Select-Object -Last 1)
            if ($terminal.Count -gt 0) { $terminal = $terminal[0]; break }
            $terminal = $null
        }
        $timedOut = $null -eq $terminal
        if ($timedOut) {
            $null = Invoke-Adb -Arguments @("-s", $DeviceId, "shell", "am", "broadcast", "-a", $stopAction, "-n", $receiverComponent) -AllowFailure
            Start-Sleep -Milliseconds 500
            $events = @(Get-BenchmarkEvents $runId)
        }
        Write-EventJsonl (Join-Path $runPath "events.jsonl") $events

        $decisions = @($events | Where-Object { [string]$_.kind -eq "DECISION" })
        $actions = @($decisions | ForEach-Object { Get-EventField $_ "action" } | Where-Object { $_ })
        $systemTools = @($decisions | ForEach-Object { Get-EventField $_ "system_tool" } | Where-Object { $_ })
        $perceptions = @($events | Where-Object { [string]$_.kind -eq "PERCEPTION" })
        $lastPackage = if ($perceptions.Count -gt 0) { Get-EventField $perceptions[-1] "package" } else { "" }
        $steps = if ($events.Count -gt 0) { [int](($events | Measure-Object -Property step -Maximum).Maximum) } else { 0 }
        $reflections = @($events | Where-Object { [string]$_.kind -eq "REFLECTION" }).Count
        $failedVerifications = @($events | Where-Object { [string]$_.kind -eq "VERIFICATION" -and (Get-EventField $_ "success") -ne "true" }).Count
        $terminalPhase = if ($null -eq $terminal) { "TIMEOUT" } else { [string]$terminal.phase }
        $duration = [Math]::Round(((Get-Date) - $started).TotalSeconds, 3)
        $oracle = Get-PropertyValue $task "oracle" $null
        $checks = New-Object System.Collections.Generic.List[object]

        $terminalActual = if ($timedOut) { "timeout" } else { $terminalPhase }
        Add-Check $checks "terminal" (-not $timedOut) "terminal event" $terminalActual
        $allowedPhases = @(Get-PropertyValue $oracle "terminal_phases" @("COMPLETE"))
        Add-Check $checks "terminal_phase" ($allowedPhases -contains $terminalPhase) ($allowedPhases -join "|") $terminalPhase
        foreach ($requiredAction in @(Get-PropertyValue $oracle "required_actions" @())) {
            Add-Check $checks "required_action:$requiredAction" ($actions -contains [string]$requiredAction) ([string]$requiredAction) ($actions -join ",")
        }
        foreach ($requiredTool in @(Get-PropertyValue $oracle "required_system_tools" @())) {
            Add-Check $checks "required_system_tool:$requiredTool" ($systemTools -contains [string]$requiredTool) ([string]$requiredTool) ($systemTools -join ",")
        }
        foreach ($forbiddenAction in @(Get-PropertyValue $oracle "forbidden_actions" @())) {
            Add-Check $checks "forbidden_action:$forbiddenAction" ($actions -notcontains [string]$forbiddenAction) ("not " + $forbiddenAction) ($actions -join ",")
        }
        $packageRegex = [string](Get-PropertyValue $oracle "package_regex" "")
        if ($packageRegex) { Add-Check $checks "package" ($lastPackage -match $packageRegex) $packageRegex $lastPackage }
        $oracleMaxSteps = Get-PropertyValue $oracle "max_steps" $null
        if ($null -ne $oracleMaxSteps) { Add-Check $checks "max_steps" ($steps -le [int]$oracleMaxSteps) ([string]$oracleMaxSteps) ([string]$steps) }
        $maxReflections = Get-PropertyValue $oracle "max_reflections" $null
        if ($null -ne $maxReflections) { Add-Check $checks "max_reflections" ($reflections -le [int]$maxReflections) ([string]$maxReflections) ([string]$reflections) }
        $maxFailed = Get-PropertyValue $oracle "max_failed_verifications" $null
        if ($null -ne $maxFailed) { Add-Check $checks "max_failed_verifications" ($failedVerifications -le [int]$maxFailed) ([string]$maxFailed) ([string]$failedVerifications) }

        $autoPass = @($checks | Where-Object { -not $_.passed }).Count -eq 0
        $manualRequired = [bool](Get-PropertyValue $oracle "manual" $false)
        $manualStatus = if ($manualRequired) { "UNVERIFIED" } else { "NOT_REQUIRED" }
        if ($manualRequired -and -not $NonInteractive) {
            $prompt = [string](Get-PropertyValue $oracle "manual_prompt" "Did the task reach the intended state?")
            $answer = Read-Host "$prompt [y/n/s]"
            $manualStatus = switch -Regex ($answer.Trim()) {
                "^(y|yes)$" { "PASS"; break }
                "^(n|no)$" { "FAIL"; break }
                default { "UNVERIFIED" }
            }
        }
        $status = if (-not $autoPass -or $manualStatus -eq "FAIL") { "FAIL" } elseif ($manualStatus -eq "UNVERIFIED") { "UNVERIFIED" } else { "PASS" }
        $failedCheckNames = @($checks | Where-Object { -not $_.passed } | ForEach-Object { $_.name })
        $reason = if ($failedCheckNames.Count -gt 0) { "Failed checks: $($failedCheckNames -join ', ')" } elseif ($status -eq "UNVERIFIED") { "Manual oracle not evaluated" } else { "All configured checks passed" }
        $result = [pscustomobject]@{
            task_id = [string]$task.id; iteration = $iteration; mode = $taskMode; status = $status
            auto_pass = $autoPass; manual_status = $manualStatus; terminal_phase = $terminalPhase; steps = $steps
            reflections = $reflections; failed_verifications = $failedVerifications; duration_seconds = $duration
            actions = $actions; system_tools = $systemTools; package = $lastPackage; reason = $reason
            checks = $checks.ToArray(); run_id = $runId
        }
        $results.Add($result)
        Write-Utf8Json (Join-Path $runPath "result.json") $result
        $color = if ($status -eq "PASS") { "Green" } elseif ($status -eq "FAIL") { "Red" } else { "Yellow" }
        Write-Host ("  {0}  phase={1} steps={2} reflections={3} time={4}s" -f $status, $terminalPhase, $steps, $reflections, $duration) -ForegroundColor $color
    }
}

$summary = [pscustomobject]@{
    schema_version = 1
    suite = [string]$suiteObject.name
    device_id = $DeviceId
    created_at = [DateTimeOffset]::Now.ToString("o")
    total_runs = $results.Count
    executed_runs = @($results.ToArray() | Where-Object { $_.status -ne "SKIP" }).Count
    pass_runs = @($results.ToArray() | Where-Object { $_.status -eq "PASS" }).Count
    fail_runs = @($results.ToArray() | Where-Object { $_.status -eq "FAIL" }).Count
    unverified_runs = @($results.ToArray() | Where-Object { $_.status -eq "UNVERIFIED" }).Count
    skipped_runs = @($results.ToArray() | Where-Object { $_.status -eq "SKIP" }).Count
    auto_pass_rate = 0
    verified_success_rate = $null
    results = $results.ToArray()
}
$executed = [int]$summary.executed_runs
if ($executed -gt 0) { $summary.auto_pass_rate = [Math]::Round(@($results.ToArray() | Where-Object { $_.auto_pass }).Count / $executed, 4) }
$verified = @($results.ToArray() | Where-Object { $_.status -in @("PASS", "FAIL") }).Count
if ($verified -gt 0) { $summary.verified_success_rate = [Math]::Round($summary.pass_runs / $verified, 4) }

Write-Utf8Json (Join-Path $sessionPath "summary.json") $summary
$results | Select-Object task_id,iteration,mode,status,auto_pass,manual_status,terminal_phase,steps,reflections,failed_verifications,duration_seconds,package,reason | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath (Join-Path $sessionPath "summary.csv")
$markdown = New-Object Text.StringBuilder
$null = $markdown.AppendLine("# Umbra benchmark result")
$null = $markdown.AppendLine("")
$null = $markdown.AppendLine("- Suite: $($summary.suite)")
$null = $markdown.AppendLine("- Device: $DeviceId")
$null = $markdown.AppendLine("- Auto pass rate: $($summary.auto_pass_rate)")
$null = $markdown.AppendLine("- Verified success rate: $($summary.verified_success_rate)")
$null = $markdown.AppendLine("")
$null = $markdown.AppendLine("| Task | Run | Mode | Status | Phase | Steps | Reflections | Seconds |")
$null = $markdown.AppendLine("|---|---:|---|---|---|---:|---:|---:|")
foreach ($item in $results) {
    $null = $markdown.AppendLine("| $($item.task_id) | $($item.iteration) | $($item.mode) | $($item.status) | $($item.terminal_phase) | $($item.steps) | $($item.reflections) | $($item.duration_seconds) |")
}
[IO.File]::WriteAllText((Join-Path $sessionPath "summary.md"), $markdown.ToString(), $utf8)

Write-Host ""
Write-Host "Benchmark complete" -ForegroundColor Green
Write-Host ("PASS={0} FAIL={1} UNVERIFIED={2} SKIP={3}" -f $summary.pass_runs, $summary.fail_runs, $summary.unverified_runs, $summary.skipped_runs)
Write-Host ("Report: {0}" -f (Join-Path $sessionPath "summary.md")) -ForegroundColor Cyan
if ($FailOnTaskFailure -and $summary.fail_runs -gt 0) { exit 2 }
