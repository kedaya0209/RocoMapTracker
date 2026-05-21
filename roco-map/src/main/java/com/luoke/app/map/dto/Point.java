package com.luoke.app.map.dto;

import lombok.Data;
import net.jcip.annotations.NotThreadSafe;


@Data
@NotThreadSafe
public class Point {

    private Double lat;

    private Double lng;
}
