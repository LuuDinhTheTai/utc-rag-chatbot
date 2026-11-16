param([string]$DatabaseName = ('utc_rag_backend_test_' + [guid]::NewGuid().ToString('N')))
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if ($DatabaseName -notmatch '^utc_rag_backend_test_[a-z0-9_]+$') { throw 'Invalid test database name' }
Push-Location $projectRoot
$created = $false
try {
    $settings = @{}
    foreach ($line in Get-Content -LiteralPath '.env') {
        if ($line -match '^([A-Z_]+)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
    }
    $config = docker compose config --format json | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Invalid Compose configuration' }
    $network = $config.name + '_utc'
    docker compose up -d --wait database
    if ($LASTEXITCODE -ne 0) { throw 'Database did not start' }
    docker compose exec -T database sh -c 'createdb -U "$POSTGRES_USER" "$1"' sh $DatabaseName
    if ($LASTEXITCODE -ne 0) { throw 'Could not create disposable test database' }
    $created = $true
    foreach ($file in Get-ChildItem -LiteralPath 'database/init' -Filter '*.sql' | Sort-Object Name) {
        Get-Content -Raw -LiteralPath $file.FullName | docker compose exec -T database sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"' sh $DatabaseName
        if ($LASTEXITCODE -ne 0) { throw ('Test schema initialization failed: ' + $file.Name) }
    }
    docker build --target build --tag utc-rag-backend-tests ./backend
    if ($LASTEXITCODE -ne 0) { throw 'Test image build failed' }
    $env:SPRING_DATASOURCE_PASSWORD = $settings['POSTGRES_PASSWORD']
    $dbUser = if ($settings['POSTGRES_USER']) { $settings['POSTGRES_USER'] } else { 'utc' }
    docker run --rm --network $network --env SPRING_DATASOURCE_PASSWORD `
        --env "SPRING_DATASOURCE_USERNAME=$dbUser" `
        --env "SPRING_DATASOURCE_URL=jdbc:postgresql://database:5432/$DatabaseName" `
        --env 'CURSOR_SECRET=test-only-cursor-secret-with-at-least-32-characters' `
        --env 'OLLAMA_BASE_URL=http://ai:11434' --env 'SLM_MODEL=qwen3:0.6b' `
        --env 'EMBEDDING_MODEL=embeddinggemma:300m-qat-q4_0' --env 'UTC_RUN_DB_TESTS=true' `
        utc-rag-backend-tests mvn -B test
    if ($LASTEXITCODE -ne 0) { throw 'Backend tests failed' }
} finally {
    Remove-Item Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
    if ($created) {
        docker compose exec -T database sh -c 'dropdb --force -U "$POSTGRES_USER" "$1"' sh $DatabaseName
        if ($LASTEXITCODE -ne 0) { Write-Warning "Test database remains: $DatabaseName" }
    }
    Pop-Location
}
