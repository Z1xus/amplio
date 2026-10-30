package us.z1x.amplio;

// values from NVSDK_NGX_DLSS_Hint_Render_Preset: preset e costs under half of k on an rtx 4060, and the performance mode default m costs a third more than k
public enum DlssModel {
    CNN(5),
    TRANSFORMER(11);

    public final int preset;

    DlssModel(int preset) {
        this.preset = preset;
    }
}
