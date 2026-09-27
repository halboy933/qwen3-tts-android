package com.qwen.tts.android.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

data class ImportedAudio(val file: File, val durationMillis: Long)

object AudioImporter {
    fun importToWav(context: Context, uri: Uri, output: File): ImportedAudio {
        val extractor = MediaExtractor()
        val afd = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: error("Could not open selected audio")
        afd.use { extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
        var track = -1; var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) { val f=extractor.getTrackFormat(i); if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true) { track=i; format=f; break } }
        require(track >= 0 && format != null) { "No audio track found" }; extractor.selectTrack(track)
        val input=format!!; val mime=input.getString(MediaFormat.KEY_MIME) ?: error("Unknown audio format")
        val codec=MediaCodec.createDecoderByType(mime); codec.configure(input,null,null,0); codec.start()
        val pcm=ByteArrayOutputStream(); val info=MediaCodec.BufferInfo(); var inputDone=false; var outputDone=false
        var rate=input.getInteger(MediaFormat.KEY_SAMPLE_RATE); var channels=input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durationUs=if(input.containsKey(MediaFormat.KEY_DURATION)) input.getLong(MediaFormat.KEY_DURATION) else 0L
        try { while(!outputDone) {
            if(!inputDone) { val i=codec.dequeueInputBuffer(10000); if(i>=0) { val b=codec.getInputBuffer(i)!!; val n=extractor.readSampleData(b,0); if(n<0){codec.queueInputBuffer(i,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true}else{codec.queueInputBuffer(i,0,n,extractor.sampleTime,0);extractor.advance()} } }
            val o=codec.dequeueOutputBuffer(info,10000)
            if(o==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){val f=codec.outputFormat;rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}
            else if(o>=0){if(info.size>0){val b=codec.getOutputBuffer(o)!!;b.position(info.offset);b.limit(info.offset+info.size);val d=ByteArray(info.size);b.get(d);pcm.write(d)};outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0;codec.releaseOutputBuffer(o,false)}
        }} finally { runCatching{codec.stop()}; codec.release(); extractor.release() }
        val mono=mono(pcm.toByteArray(),channels); val converted=resample(mono,rate,24000)
        output.parentFile?.mkdirs(); output.outputStream().use{wav(it,converted,24000)}
        val calculated=(converted.size/2)*1000L/24000L
        return ImportedAudio(output,if(durationUs>0) durationUs/1000L else calculated)
    }
    private fun mono(src:ByteArray,channels:Int):ShortArray { val ch=channels.coerceAtLeast(1);val frames=(src.size/2)/ch;val out=ShortArray(frames);var p=0;for(i in 0 until frames){var sum=0;repeat(ch){val lo=src[p++].toInt() and 255;val hi=src[p++].toInt();sum+=((hi shl 8) or lo).toShort().toInt()};out[i]=(sum/ch).toShort()};return out }
    private fun resample(src:ShortArray,from:Int,to:Int):ByteArray { require(src.isNotEmpty()){"Decoded audio is empty"};val n=(src.size.toLong()*to/from.coerceAtLeast(1)).toInt().coerceAtLeast(1);val out=ByteArray(n*2);for(i in 0 until n){val pos=i.toDouble()*from/to;val a=pos.toInt().coerceIn(0,src.lastIndex);val b=(a+1).coerceAtMost(src.lastIndex);val f=pos-a;val v=(src[a]*(1-f)+src[b]*f).toInt().coerceIn(-32768,32767);out[i*2]=(v and 255).toByte();out[i*2+1]=((v shr 8) and 255).toByte()};return out }
    private fun wav(out:OutputStream,pcm:ByteArray,rate:Int){fun a(s:String)=out.write(s.toByteArray(Charsets.US_ASCII));fun i(v:Int){repeat(4){out.write((v shr (8*it)) and 255)}};fun h(v:Int){repeat(2){out.write((v shr (8*it)) and 255)}};a("RIFF");i(36+pcm.size);a("WAVE");a("fmt ");i(16);h(1);h(1);i(rate);i(rate*2);h(2);h(16);a("data");i(pcm.size);out.write(pcm)}
}
