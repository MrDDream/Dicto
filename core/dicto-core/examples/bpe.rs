//! Affiche le découpage BPE d'une phrase : `bpe <bpe.vocab> "LIGHT UP"`.
fn main() {
    let mut args = std::env::args().skip(1);
    let vocab = dicto_core::wake::parse_vocab(&std::fs::read_to_string(args.next().unwrap()).unwrap());
    for p in args {
        println!("{p} → {}", dicto_core::wake::bpe_encode(&vocab, &p).join(" "));
    }
}
