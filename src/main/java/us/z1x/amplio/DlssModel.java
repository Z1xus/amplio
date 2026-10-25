package us.z1x.amplio;

// the cnn model costs about a third of the transformer on an rtx 4060, values from NVSDK_NGX_DLSS_Hint_Render_Preset
public enum DlssModel {
	CNN(5),
	TRANSFORMER(0);

	public final int preset;

	DlssModel(int preset) {
		this.preset = preset;
	}
}
