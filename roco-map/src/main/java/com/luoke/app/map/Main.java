package com.luoke.app.map;

import java.io.File;

public class Main {

    static void main() {
        String template = "{\"glob\": \"source/map/WorldMap_Show_tiles/%s\"},";

        String imgPath = Main.class.getClassLoader().getResource("source/map/WorldMap_Show_tiles").getPath();

        File dir = new File(imgPath);

        for (File childDir : dir.listFiles()) {
            for (String filename : childDir.list()) {
                System.out.println(template.formatted(childDir.getName() + "/" + filename));
            }
        }
    }
}
