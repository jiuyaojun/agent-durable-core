<#
  停止本项目专属的 MySQL 开发实例。
  只按端口 3307 精确定位进程，不会影响系统那个 3306 的 MySQL。
#>
$port = 3307
$conns = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue

if (-not $conns) {
    Write-Host "实例未在运行（端口 $port 无监听）。"
    exit 0
}

foreach ($conn in $conns) {
    $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
    if ($proc -and $proc.ProcessName -eq 'mysqld') {
        Write-Host "停止 mysqld (PID $($proc.Id))"
        Stop-Process -Id $proc.Id -Force
    }
    else {
        Write-Host "端口 $port 被非 mysqld 进程占用，跳过以确保安全。"
    }
}

Start-Sleep -Seconds 2
if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
    Write-Host "仍未停止，请手动检查。"
}
else {
    Write-Host "已停止。数据保留在 C:\Users\xuchenxiang\agent-durable-devdb\data"
}
