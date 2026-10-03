from cyclone_device_gateway.vmos import (
    CYCLONE_MOBILE_PACKAGE,
    DEVICE_GATEWAY_COMPONENT,
    PHONE_MUTATION_ENGINE,
    VMOS_ARCHITECTURE,
    VmosArchitecture,
)


def test_vmos_is_transport_not_phone_mutation_engine():
    architecture = VMOS_ARCHITECTURE
    architecture.validate()

    assert architecture.provider == "VMOS"
    assert architecture.role.value == "ANDROID_HOST"
    assert architecture.mobile_package == "com.cyclone.mobile"
    assert architecture.gateway_component == "apps/device-gateway"
    assert architecture.mutation_engine == "PhoneToolExecutor"
    assert architecture.provider_native_mutation_allowed is False
    assert architecture.generic_shell_exposed_to_model is False
    assert architecture.control_path == (
        "Cyclone One",
        "Device Gateway",
        "Cyclone Mobile local gateway",
        "PhoneToolExecutor",
        "Android Accessibility/root helpers",
    )


def test_vmos_bootstrap_is_separate_from_agent_control():
    architecture = VMOS_ARCHITECTURE

    assert architecture.bootstrap_transport.value == "REMOTE_ADB"
    assert architecture.lifecycle_owner.value == "VMOS_OPENAPI"
    assert architecture.bootstrap_path == (
        "VMOS OpenAPI",
        "VMOS remote ADB",
        CYCLONE_MOBILE_PACKAGE,
        "Cyclone Mobile local gateway",
    )
    assert "VMOS remote ADB" not in architecture.control_path


def test_architecture_rejects_direct_vmos_mutation_or_generic_shell():
    invalid_native = VmosArchitecture(provider_native_mutation_allowed=True)
    invalid_shell = VmosArchitecture(generic_shell_exposed_to_model=True)

    for architecture in (invalid_native, invalid_shell):
        try:
            architecture.validate()
        except ValueError:
            pass
        else:
            raise AssertionError("unsafe VMOS architecture was accepted")


def test_public_contract_is_stable_for_setup_and_ui_consumers():
    public = VMOS_ARCHITECTURE.public()

    assert public["architectureVersion"] == 1
    assert public["role"] == "ANDROID_HOST"
    assert public["bootstrap_transport"] == "REMOTE_ADB"
    assert public["optional_view_transport"] == "VMOS_H5"
    assert public["controlPath"][-2] == PHONE_MUTATION_ENGINE
    assert public["gateway_component"] == DEVICE_GATEWAY_COMPONENT
