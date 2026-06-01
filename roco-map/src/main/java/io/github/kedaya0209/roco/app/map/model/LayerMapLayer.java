package io.github.kedaya0209.roco.app.map.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import net.jcip.annotations.NotThreadSafe;

@Data
@NotThreadSafe
public class LayerMapLayer {

    private int id;

    private String file;

    @JsonProperty("display_name")
    private String displayName;

    @JsonProperty("camera_center_x")
    private double cameraCenterX;

    @JsonProperty("camera_center_y")
    private double cameraCenterY;

    @JsonProperty("ortho_width")
    private double orthoWidth;
}
