# Transport only: shared justfiles define the workflows and their arguments.
# PowerShell 5.1's native invocation drops embedded quotes/empty arguments.
# Encode the Windows argv explicitly and preserve the child's exit status.
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $env:EXOTICA_ROOT

# Keep options such as -f out of PowerShell's parameter binder.
$Target = $args[0]
if ($Target -notin @('just', 'compose')) {
    throw "Expected a just or compose transport target."
}
$executable = 'just'
$commandArguments = @($args | Select-Object -Skip 1)
if ($Target -eq 'compose') {
    if ($env:DOCKER_COMPOSE_BIN) {
        $executable = $env:DOCKER_COMPOSE_BIN
    } else {
        $executable = 'docker'
        $commandArguments = @('compose') + $commandArguments
    }
}

$encodedArguments = foreach ($argument in $commandArguments) {
    # Escape backslashes preceding quotes and the final closing quote.
    $escaped = [regex]::Replace($argument, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    '"' + $escaped + '"'
}
$startInfo = New-Object System.Diagnostics.ProcessStartInfo
$startInfo.FileName = (Get-Command $executable -CommandType Application -ErrorAction Stop).Source
$startInfo.Arguments = $encodedArguments -join ' '
$startInfo.UseShellExecute = $false
$process = New-Object System.Diagnostics.Process
$process.StartInfo = $startInfo
[void] $process.Start()
$process.WaitForExit()
exit $process.ExitCode
