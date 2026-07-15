<#
.SYNOPSIS
Script chạy toàn bộ Unit Test cho event-service

.DESCRIPTION
Script này sẽ tự động lùi về thư mục gốc của event-service và kích hoạt lệnh Maven test, 
sau đó trả lại đường dẫn cũ để tiện thao tác.
#>

$OriginalLocation = Get-Location

try {
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "   PROCESSING UNIT TEST (EVENT-SERVICE)  " -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan

    # Di chuyển về thư mục gốc của event-service (chứa pom.xml)
    Set-Location -Path "..\..\"

    # Chạy lệnh maven
    .\mvnw clean test

    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "             COMPLETE UNIT TEST              " -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
}
finally {
    # Luôn trả về thư mục cũ dù có lỗi hay không
    Set-Location -Path $OriginalLocation
}
