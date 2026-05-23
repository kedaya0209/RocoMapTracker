package com.luoke.app;

import net.jcip.annotations.ThreadSafe;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@ThreadSafe
public class MoveValidationDataSet {

    static void main() throws IOException {
        //原始数据集
        String origin = "C:\\Users\\tangh\\Desktop\\dataset\\train_set";
        //已经验证过的数据集
        String valid = "C:\\Users\\tangh\\Desktop\\dataset\\validation";
        String validPca = "C:\\Users\\tangh\\Desktop\\dataset\\validation_pca";
        //结果数据集
        String result = "C:\\Users\\tangh\\Desktop\\dataset\\result";

        File resultDir = new File(result);
        if (!resultDir.exists()) {
            resultDir.mkdirs();
        }
        //原始数据集名单
        File originFile = new File(origin);
        String[] originFileNames = originFile.list();

        //需要移动的名单
        File validFile = new File(valid);
        String[] validFileNames = Optional.ofNullable(validFile.list()).orElse(new String[0]);
        Set<String> moveFiles = new HashSet<>(List.of(validFileNames));

        for (String originFileName : originFileNames) {
            if (!moveFiles.contains(originFileName)) {
                Files.deleteIfExists(Path.of(origin ,originFileName));
            }
        }

        //另一种算法验证的名单
        File validPcaFile = new File(validPca);
        String[] validPcaFileNames = Optional.ofNullable(validPcaFile.list()).orElse(new String[0]);
        Map<String, String> moveFileMap = Arrays.stream(validPcaFileNames).collect(Collectors.toMap(name -> name.split("_")[1], name -> name));

        for (String originFileName : originFileNames) {
            if (moveFileMap.containsKey(originFileName.split("_")[1])) {
                //移动
                Files.move(Path.of(origin ,originFileName), Path.of(result, originFileName));
            }
        }

    }

}
