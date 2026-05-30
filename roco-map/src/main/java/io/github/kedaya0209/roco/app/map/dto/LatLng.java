package io.github.kedaya0209.roco.app.map.dto;

import lombok.Data;
import net.jcip.annotations.NotThreadSafe;


@Data
@NotThreadSafe
public class LatLng {

    private Double lat;

    private Double lng;
}
