# Changelog

## 1.1

- **Quoted replies**: swipe a message to the right (or Reply in its menu); replies go out as IRCv3
  `+draft/reply` and show the quoted message, which takes you to the original when tapped.
- **Image authors**: images sent from the image search carry their artist and it shows under the
  picture in the chat.
- **User card**: tapping a user opens a floating card with their VitaIRC profile and the WHOIS
  answer.
- **VitaIRC profiles**: name, bio, picture and public favorites, kept on the phone and sent over
  IRC to whoever asks (no external server; see [docs/PROFILE-PROTOCOL.md](docs/PROFILE-PROTOCOL.md)).
  Works between Android, iPhone and PS Vita.
- **Floating video player**: videos from the chat play in a small window you can keep open while
  chatting; Imgur GIFs (MP4) play inline, muted and looping.

## 1.0

First version of VitaIRC for Android, with the features of VitaIRC 1.4 for PS Vita:

- Several servers, SSL/TLS, SASL / NickServ / server password, reconnection, flood protection.
- Bouncers: IRCv3 chat history (missed messages with their real time, older ones on scroll),
  reactions, soju FILEHOST uploads.
- Translation with Google or ChatGPT (incoming, outgoing, one message, language per channel).
- Image uploads (Litterbox, ImgBB, Imgur, soju) from the gallery or the camera.
- Link previews, video player, image viewer with zoom.
- Image search (Safebooru, Danbooru, Sankaku) with favorites.
- Channel browser with search, user list with operator actions, quick replies, nick completion,
  search in a channel, mute, ignore, highlight words.

Only on Android: background connection with notifications, tablet layout, hardware keyboard support,
and updates installed from the app (download + Android's installer).
