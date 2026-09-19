<#
  启动本项目专属的 MySQL 开发实例（端口 3307）。

  为什么不直接用系统那个 3306 的 MySQL：
    系统实例的 root 密码不属于本项目，项目不应该依赖它。
    这个专属实例的数据目录独立、密码已知、可随时删除重建，
    换一台机器也能用同一个脚本复现出完全一样的环境。

  数据目录：C:\Users\xuchenxiang\agent-durable-devdb
  账号：durable / durable_dev_pwd    库：durable_test
#>
$ErrorActionPreference = 'Stop'

$basedir = 'C:\Program Files\MySQL\MySQL Server 8.0'
$root    = 'C:\Users\xuchenxiang\agent-durable-devdb'
$port    = 3307

if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
    Write-Host "实例已在运行（端口 $port），无需重复启动。"
    exit 0
}

if (-not (Test-Path "$root\data")) {
    Write-Host "首次运行：初始化数据目录..."
    New-Item -ItemType Directory -Path "$root\data" -Force | Out-Null
    @"
[mysqld]
basedir=C:/Program Files/MySQL/MySQL Server 8.0
datadir=C:/Users/xuchenxiang/agent-durable-devdb/data
port=3307
bind-address=127.0.0.1
mysqlx=OFF
log-error=C:/Users/xuchenxiang/agent-durable-devdb/error.log
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci
"@ | Set-Content -Path "$root\my.ini" -Encoding ASCII

    & "$basedir\bin\mysqld.exe" --defaults-file="$root\my.ini" --initialize-insecure --console 2>&1 |
        Select-Object -Last 3
}

Write-Host "启动 mysqld..."
Start-Process -FilePath "$basedir\bin\mysqld.exe" -ArgumentList "--defaults-file=$root\my.ini" -WindowStyle Hidden

$ready = $false
for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Milliseconds 800
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
        $ready = $true; break
    }
}

if (-not $ready) {
    Write-Host "启动失败，错误日志末尾："
    Get-Content "$root\error.log" -Tail 20
    exit 1
}

# 幂等地建库与建号
& "$basedir\bin\mysql.exe" -u root -h 127.0.0.1 -P $port --protocol=TCP -e @"
CREATE DATABASE IF NOT EXISTS durable_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'durable'@'localhost' IDENTIFIED BY 'durable_dev_pwd';
CREATE USER IF NOT EXISTS 'durable'@'127.0.0.1' IDENTIFIED BY 'durable_dev_pwd';
GRANT ALL PRIVILEGES ON durable_test.* TO 'durable'@'localhost';
GRANT ALL PRIVILEGES ON durable_test.* TO 'durable'@'127.0.0.1';
FLUSH PRIVILEGES;
"@ 2>&1 | Where-Object { $_ -notmatch 'Warning' }

Write-Host "就绪：mysql://127.0.0.1:$port/durable_test  (durable / durable_dev_pwd)"
