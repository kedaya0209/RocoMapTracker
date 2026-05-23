package com.luoke.app.map;

import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.NotThreadSafe;

import java.io.File;

@Slf4j
@NotThreadSafe
public class Main {

    static void main() {
        String template = "{\"glob\": \"source/map/WorldMap_Show_tiles/%s\"},";

        String imgPath = Main.class.getClassLoader().getResource("source/map/WorldMap_Show_tiles").getPath();

        File dir = new File(imgPath);

        for (File childDir : dir.listFiles()) {
            for (String filename : childDir.list()) {
                log.info("{}", template.formatted(childDir.getName() + "/" + filename));
            }
        }
    }
}
