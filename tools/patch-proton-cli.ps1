param([Parameter(Mandatory = $true)][string]$Path)

$bytes = [IO.File]::ReadAllBytes($Path)
$ascii = [Text.Encoding]::ASCII
$needleText = 'function vE(J){let Z=OJ1(J);if(!Z)return;'
$replacementText = 'function vE(J){return;'.PadRight($needleText.Length)
$needle = $ascii.GetBytes($needleText)
$replacement = $ascii.GetBytes($replacementText)
$matches = [Collections.Generic.List[int]]::new()

for ($offset = 0; $offset -le $bytes.Length - $needle.Length; $offset++) {
    $equal = $true
    for ($index = 0; $index -lt $needle.Length; $index++) {
        if ($bytes[$offset + $index] -ne $needle[$index]) {
            $equal = $false
            break
        }
    }
    if ($equal) { $matches.Add($offset) }
}

if ($matches.Count -ne 1) {
    throw "Expected one Proton browser-launch function, found $($matches.Count)."
}

[Array]::Copy($replacement, 0, $bytes, $matches[0], $replacement.Length)
[IO.File]::WriteAllBytes($Path, $bytes)
Write-Output "Disabled automatic xdg-open call at byte $($matches[0])."

