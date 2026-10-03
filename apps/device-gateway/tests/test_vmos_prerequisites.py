from cyclone_device_gateway.vmos.prerequisites import (
    BUNDLED_ADB_RELATIVE_PATH,
    BUNDLED_PLATFORM_TOOLS_VERSION,
    CURRENT_MOBILE_APK,
    CURRENT_MOBILE_BASELINE,
    CURRENT_MOBILE_SHA256,
    CURRENT_ONE_INSTALLER,
    CURRENT_ONE_INSTALL_ROOT,
    CURRENT_ONE_VERSION,
    PrerequisiteSnapshot,
    VMOS_EDGE_ANDROID15_CONTROL_API_MIN_IMAGE,
    VMOS_EDGE_CONTROL_API_MIN_CBS,
    VMOS_REMOTE_ADB_DEFAULT_HOURS,
    installation_plan,
    validate_prerequisites,
)


def _ready(**overrides):
    values = dict(
        windows_major=11,
        cyclone_one_version="1.5.1",
        adb_available=True,
        adb_version="37.0.1",
        mobile_apk=r"C:\Cyclone\Cyclone-4.3.6.apk",
        mobile_sha256=CURRENT_MOBILE_SHA256,
        vmos_android_major=15,
        vmos_adb_account_authorized=True,
        vmos_adb_session_open=True,
        vmos_image=None,
        needs_edge_control_api=False,
        vmos_edge_cbs_version=None,
    )
    values.update(overrides)
    return PrerequisiteSnapshot(**values)


def test_install_order_is_single_clear_cloud_path():
    plan = installation_plan()

    assert [item["order"] for item in plan] == [1, 2, 3, 4, 5]
    assert [item["component"] for item in plan] == [
        "Cyclone One",
        "VMOS Cloud",
        "VMOS Remote ADB",
        "Cyclone Mobile",
        "Install + launch",
    ]
    assert CURRENT_ONE_VERSION == "1.5.1"
    assert CURRENT_ONE_INSTALLER == "Cyclone-PC-Companion-1.5.1-Setup.exe"
    assert CURRENT_ONE_INSTALL_ROOT == r"%LOCALAPPDATA%\Cyclone One"
    assert BUNDLED_PLATFORM_TOOLS_VERSION == "37.0.1"
    assert BUNDLED_ADB_RELATIVE_PATH == r"android-platform-tools\adb.exe"
    assert CURRENT_MOBILE_BASELINE == "4.3.6"
    assert CURRENT_MOBILE_APK == "Cyclone-4.3.6.apk"
    assert VMOS_REMOTE_ADB_DEFAULT_HOURS == 24
    assert "pinned Android Platform-Tools 37.0.1" in plan[0]["reason"]
    assert "no separate PC Agent or ADB installation" in plan[0]["reason"]
    assert "minSdk is 33" in plan[1]["reason"]
    assert "generated SSH/key" in plan[2]["action"]
    assert BUNDLED_ADB_RELATIVE_PATH in plan[2]["action"]
    assert "check-prerequisites.ps1" in plan[4]["action"]
    assert "bundled adb.exe" in plan[4]["action"]


def test_prerequisites_accept_preferred_android15_cloud_path():
    result = validate_prerequisites(_ready())

    assert result.ready is True
    assert result.blockers == ()
    assert result.warnings == ()


def test_android13_and_14_are_supported_compatibility_targets():
    for major in (13, 14):
        result = validate_prerequisites(_ready(vmos_android_major=major))
        assert result.ready is True
        assert result.warnings == (f"Android 15 is the preferred VMOS target; Android {major} is compatibility mode.",)


def test_android10_is_rejected_by_cyclone_min_sdk():
    result = validate_prerequisites(_ready(vmos_android_major=10))

    assert result.ready is False
    assert any("minSdk 33" in item for item in result.blockers)


def test_old_one_is_a_blocker_and_newer_one_is_only_unvalidated_warning():
    old = validate_prerequisites(_ready(cyclone_one_version="1.5.0"))
    newer = validate_prerequisites(_ready(cyclone_one_version="1.5.2"))

    assert old.ready is False
    assert any("1.5.1 or newer" in item for item in old.blockers)
    assert newer.ready is True
    assert newer.warnings == ("Validated VMOS baseline is Cyclone One 1.5.1; found newer 1.5.2.",)


def test_bundled_adb_is_required_and_repair_points_back_to_one():
    result = validate_prerequisites(_ready(adb_available=False))

    assert result.ready is False
    assert any("bundled adb.exe" in item for item in result.blockers)
    assert any("Repair/reinstall One 1.5.1" in item for item in result.blockers)
    assert any(BUNDLED_ADB_RELATIVE_PATH in item for item in result.blockers)
    assert not any("install Android Studio" in item.lower() for item in result.blockers)


def test_bundled_adb_version_floor_is_enforced():
    old = validate_prerequisites(_ready(adb_version="36.0.0"))
    current = validate_prerequisites(_ready(adb_version="37.0.1"))
    newer = validate_prerequisites(_ready(adb_version="37.0.2"))

    assert old.ready is False
    assert any("Platform-Tools 37.0.1 or newer" in item for item in old.blockers)
    assert current.ready is True
    assert current.warnings == ()
    assert newer.ready is True
    assert newer.warnings == ("Validated bundled ADB baseline is Platform-Tools 37.0.1; found 37.0.2.",)


def test_remote_adb_authorization_and_live_session_are_real_blockers():
    unauthorized = validate_prerequisites(_ready(vmos_adb_account_authorized=False))
    closed = validate_prerequisites(_ready(vmos_adb_session_open=False))

    assert unauthorized.ready is False
    assert any("remote ADB permission" in item for item in unauthorized.blockers)
    assert closed.ready is False
    assert any("Local Debugging" in item for item in closed.blockers)


def test_published_mobile_hash_is_required_for_exact_436_asset():
    missing = validate_prerequisites(_ready(mobile_sha256=None))
    wrong = validate_prerequisites(_ready(mobile_sha256="0" * 64))

    assert missing.ready is True
    assert any("Verify Cyclone-4.3.6.apk SHA-256" in item for item in missing.warnings)
    assert wrong.ready is False
    assert any("does not match the published release asset" in item for item in wrong.blockers)


def test_windows_mobile_path_cannot_bypass_exact_asset_hash_guard():
    result = validate_prerequisites(_ready(
        mobile_apk=r"C:\Downloads\Cyclone-4.3.6.apk",
        mobile_sha256="0" * 64,
    ))

    assert result.ready is False
    assert any("does not match the published release asset" in item for item in result.blockers)


def test_older_mobile_filename_is_rejected():
    result = validate_prerequisites(_ready(
        mobile_apk=r"C:\Cyclone\Cyclone-4.3.5.apk",
        mobile_sha256=None,
    ))

    assert result.ready is False
    assert any("4.3.6 or newer" in item for item in result.blockers)


def test_hosted_adb_path_does_not_require_edge_image_identity():
    result = validate_prerequisites(_ready(vmos_image=None, needs_edge_control_api=False))

    assert result.ready is True
    assert result.blockers == ()


def test_edge_control_api_uses_current_documented_android15_image_floor_and_cbs():
    baseline = validate_prerequisites(_ready(
        needs_edge_control_api=True,
        vmos_edge_cbs_version=VMOS_EDGE_CONTROL_API_MIN_CBS,
        vmos_image=VMOS_EDGE_ANDROID15_CONTROL_API_MIN_IMAGE,
    ))
    old_image = validate_prerequisites(_ready(
        needs_edge_control_api=True,
        vmos_edge_cbs_version=VMOS_EDGE_CONTROL_API_MIN_CBS,
        vmos_image="vcloud_android15_edge_20251227",
    ))

    assert baseline.ready is True
    assert baseline.warnings == ()
    assert old_image.ready is False
    assert any(VMOS_EDGE_ANDROID15_CONTROL_API_MIN_IMAGE in item for item in old_image.blockers)


def test_missing_pc_runtime_is_expressed_as_one_install_not_two():
    result = validate_prerequisites(_ready(
        cyclone_one_version=None,
        adb_available=False,
        adb_version=None,
        mobile_apk=None,
        mobile_sha256=None,
        vmos_android_major=None,
        vmos_adb_account_authorized=False,
        vmos_adb_session_open=False,
    ))

    assert result.ready is False
    assert any("Install Cyclone One 1.5.1" in item for item in result.blockers)
    assert any("Repair/reinstall One 1.5.1" in item for item in result.blockers)
    assert not any("install PC Agent" in item.lower() for item in result.blockers)
    assert not any("install Android Studio" in item.lower() for item in result.blockers)
