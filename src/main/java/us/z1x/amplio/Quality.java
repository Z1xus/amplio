package us.z1x.amplio;

// render scale presets shared by every upscaler, ngx values from NVSDK_NGX_PerfQuality_Value
public enum Quality {
	NATIVE(1.0F, 5),
	QUALITY(0.667F, 2),
	BALANCED(0.58F, 1),
	PERFORMANCE(0.5F, 0),
	ULTRA_PERFORMANCE(0.333F, 3);

	public final float scale;
	public final int ngx;

	Quality(float scale, int ngx) {
		this.scale = scale;
		this.ngx = ngx;
	}
}
