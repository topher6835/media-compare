package io.github.topher6835.mediacompare.analysis;

import java.util.List;

record FfprobeOutput(FfprobeFormat format, List<FfprobeStream> streams) {
}

record FfprobeFormat(
        String formatName,
        String duration,
        String majorBrand,
        String compatibleBrands) {
}

record FfprobeStream(
        int index,
        String codecType,
        String codecName,
        Integer width,
        Integer height,
        FfprobeDisposition disposition) {
}

record FfprobeDisposition(
        boolean defaultStream,
        boolean attachedPicture,
        boolean timedThumbnail,
        boolean stillImage) {
}
