package com.kbmah.linkedin;

import java.nio.file.Path;

record SaveResult(Path outputPath, int savedCount, boolean duplicateFound, int maxConsecutiveDuplicates) {
}
