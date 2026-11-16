//! Décodage d'un fichier audio en PCM 16 bits entrelacé (Symphonia, sans dépendance système).

use std::fs::File;
use std::path::Path;

use symphonia::core::audio::SampleBuffer;
use symphonia::core::codecs::{CODEC_TYPE_NULL, DecoderOptions};
use symphonia::core::errors::Error;
use symphonia::core::formats::FormatOptions;
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;

pub struct Decoded {
    /// PCM 16 bits little-endian, entrelacé.
    pub pcm: Vec<u8>,
    pub sample_rate: u32,
    pub channels: u16,
}

pub fn decode(path: &Path) -> Result<Decoded, String> {
    let file = File::open(path).map_err(|e| format!("Fichier illisible : {e}"))?;
    let mss = MediaSourceStream::new(Box::new(file), Default::default());
    let mut hint = Hint::new();
    if let Some(ext) = path.extension().and_then(|e| e.to_str()) {
        hint.with_extension(ext);
    }
    let probed = symphonia::default::get_probe()
        .format(&hint, mss, &FormatOptions::default(), &MetadataOptions::default())
        .map_err(|_| "Format audio non pris en charge (MP3, M4A, WAV, FLAC et OGG Vorbis le sont).".to_string())?;
    let mut format = probed.format;
    let track = format
        .tracks()
        .iter()
        .find(|t| t.codec_params.codec != CODEC_TYPE_NULL)
        .ok_or("Aucune piste audio dans ce fichier.")?;
    let track_id = track.id;
    let mut decoder = symphonia::default::get_codecs()
        .make(&track.codec_params, &DecoderOptions::default())
        .map_err(|_| "Codec audio non pris en charge (l'Opus des messages vocaux, par exemple).".to_string())?;

    let mut pcm = Vec::new();
    let mut sample_rate = track.codec_params.sample_rate.unwrap_or(0);
    let mut channels = track.codec_params.channels.map(|c| c.count() as u16).unwrap_or(0);
    let mut buf: Option<SampleBuffer<i16>> = None;
    loop {
        let packet = match format.next_packet() {
            Ok(p) => p,
            Err(Error::IoError(e)) if e.kind() == std::io::ErrorKind::UnexpectedEof => break,
            Err(Error::ResetRequired) => break,
            Err(e) => return Err(format!("Lecture interrompue : {e}")),
        };
        if packet.track_id() != track_id {
            continue;
        }
        let decoded = match decoder.decode(&packet) {
            Ok(d) => d,
            // Paquet abîmé : on passe au suivant.
            Err(Error::DecodeError(_)) => continue,
            Err(e) => return Err(format!("Décodage impossible : {e}")),
        };
        let spec = *decoded.spec();
        sample_rate = spec.rate;
        channels = spec.channels.count() as u16;
        let b = buf.get_or_insert_with(|| SampleBuffer::new(decoded.capacity() as u64, spec));
        if b.capacity() < decoded.capacity() * spec.channels.count() {
            *b = SampleBuffer::new(decoded.capacity() as u64, spec);
        }
        b.copy_interleaved_ref(decoded);
        pcm.extend(b.samples().iter().flat_map(|s| s.to_le_bytes()));
    }
    if pcm.is_empty() || sample_rate == 0 || channels == 0 {
        return Err("Ce fichier ne contient pas de son.".into());
    }
    Ok(Decoded { pcm, sample_rate, channels })
}
