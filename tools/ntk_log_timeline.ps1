param(
    [Parameter(Mandatory=$true)]
    [string]$Logcat
)

if (!(Test-Path -LiteralPath $Logcat)) {
    Write-Error "Logcat not found: $Logcat"
    exit 1
}

$pattern = @(
    'ntk_true_random_case_start',
    'Lifecycle status change: ml\.melun\.mangaview\.activity\.ReaderV2Activity.*(CREATED|STARTED|RESUMED)',
    'reader_activity_start_session',
    'reader_repository_stage stage=load_start',
    'reader_repository_stage stage=cached_urls_checked',
    'ntk_rsc_payload',
    'ntk_page_fetch',
    'ntk_viewer_api_prefetch_token',
    'ntk_images_api_start',
    'ntk_images_api_pre_ack_miss',
    'ntk_images_api_webview_ack_preflight',
    'ntk_webview_ack_preflight',
    'ntk_native_ack_challenge',
    'directAckProof',
    'guardNativePreGuard',
    'ackOnlySyncGuard',
    'ntk_viewer_ad_bridge',
    'ntk_server_ack_success',
    'ntk_images_api_first_url',
    'ntk_images_api_trusted_result',
    'ntk_images_api_after_ack_proof_success',
    'reader_early_ntk_urls_remember',
    'reader_repository_stage stage=early_urls',
    'reader_repository_stage stage=request_foreground',
    'reader_repository_stage stage=fetch_initial_after_early',
    'reader_repository_stage stage=fetch_initial_done_after_early',
    'ntk_generated_direct_extension_probe',
    'ntk_generated_page_count_probe_deferred',
    'ntk_first_api_image_stream_start',
    'ntk_image_header_probe',
    'generated_initial_range_first',
    'generated_range_first_chunk_retry',
    'download_start',
    'download_body',
    'download_done',
    'download_initial_recovery',
    'ntk_anchor_asset_stream_satisfied',
    'reader_ntk_pre_anchor_request',
    'ntk_image_permit_granted',
    'reader_ntk_page_perf .*decode_ready',
    'reader_anchor_delivery_queue_delay',
    'initial_draw_gate',
    'foreground_stream',
    'foreground_race',
    'ntk_quic_image_result',
    'Choreographer.*Skipped',
    'reader_open_to_first_drawable',
    'ntk_true_random_first_drawable',
    'page_error'
) -join '|'

Select-String -LiteralPath $Logcat -Pattern $pattern |
    ForEach-Object {
        $line = $_.Line
        if ($line -match '^\d\d-\d\d\s+(\d\d:\d\d:\d\d\.\d\d\d)\s+\S+/\S+\(\s*\d+\):\s+(.*)$') {
            "{0} {1}" -f $matches[1], $matches[2]
        } elseif ($line -match '^\d\d-\d\d\s+(\d\d:\d\d:\d\d\.\d\d\d)\s+\S+/\S+\(\s*\d+\):\s+(.*)$') {
            "{0} {1}" -f $matches[1], $matches[2]
        } else {
            $line
        }
    }
