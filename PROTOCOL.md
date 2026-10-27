# Watch ↔ phone protocol

The [Phone & SMS watchapp](https://github.com/loxK/pebble-phone-sms) and this
Android companion talk over AppMessage, carried by PebbleKit Android 2 through
the Pebble mobile app. Keys are numbered explicitly in the `package.json` of
the watchapp and mirrored in
`app/src/main/java/io/github/loxk/pebblephone/Protocol.kt`. Both lists must change
together, and so must the commands of `src/c/protocol.h` in the watchapp.

## Keys

| Key       | Id | Type    | Meaning                                              |
|-----------|----|---------|------------------------------------------------------|
| `Cmd`     | 1  | uint8   | Command, see below                                   |
| `Seq`     | 2  | int32   | Request number, echoed in the reply                  |
| `Id`      | 3  | int32   | Contact, thread or message id                        |
| `Offset`  | 4  | int32   | Index of the first item of a page                    |
| `Total`   | 5  | int32   | Number of items in the whole list                    |
| `Items`   | 6  | bytes   | Packed records of a page                             |
| `Text`    | 7  | cstring | Free text: a letter, a message body, a phone number  |
| `Address` | 8  | cstring | Phone number                                         |
| `Name`    | 9  | cstring | Display name                                         |
| `State`   | 10 | uint8   | Call state                                           |
| `Flags`   | 11 | int32   | Bit field, meaning depends on the command            |
| `Elapsed` | 12 | int32   | Seconds since the call connected                     |
| `Status`  | 13 | int32   | Result code                                          |
| `Budget`  | 14 | int32   | Largest `Items` blob the watch accepts, in bytes     |
| `Size`    | 15 | int32   | Text and icon size of the watch menus, see below     |
| `BodySize`| 16 | int32   | Text size of the message reading screen, same values |

## Commands

Requests go from the watch to the phone. A reply carries the same `Cmd` and
`Seq` as its request.

| Cmd | Name           | Request                    | Reply                               |
|-----|----------------|----------------------------|-------------------------------------|
| 1   | `HELLO`        | `Budget`                   | `Flags` (capabilities), `Size`, `BodySize`, `Status`, and `Items` holding the launcher event when there is one |
| 2   | `FAVORITES`    | `Offset`                   | page                                |
| 3   | `LETTERS`      |                            | page                                |
| 4   | `CONTACTS`     | `Text` (letter), `Offset`  | page                                |
| 5   | `NUMBERS`      | `Id` (contact)             | page                                |
| 6   | `CALLS`        | `Offset`                   | page                                |
| 7   | `THREADS`      | `Offset`                   | page                                |
| 8   | `MESSAGES`     | `Id` (thread), `Offset`    | page                                |
| 9   | `MESSAGE_BODY` | `Id` (message, negative for an MMS) | `Text`                     |
| 10  | `DIAL`         | `Address`                  | `Status`, `Flags` bit 0: the call will be followed by `CALL_STATE` |
| 11  | `SEND_SMS`     | `Address`, `Text`, `Id` (conversation answered, 0 for a new one) | `Status`, sent once the radio is done |
| 12  | `HANG_UP`      |                            | none, a `CALL_STATE` follows        |
| 13  | `MUTE`         |                            | none, a `CALL_STATE` follows        |
| 14  | `AUDIO_ROUTE`  |                            | none, a `CALL_STATE` follows        |
| 15  | `CALL_STATE`   |                            | `CALL_STATE`                        |
| 16  | `PHOTO`        | `Id` or `Address`, `Offset`, `Flags` | photo page               |
| 17  | `SEARCH`       | `Text` (spoken query), `Offset` | page, and `Text`: the query in Latin letters |
| 18  | `REPLIES`      |                            | page of quick replies               |
| 19  | `OPEN_THREAD`  | `Address`, `Id` (conversation) | `Status`: 0 once the SMS app opens on the phone, 1 without the watch association that lets the app open it |

The phone also pushes `CALL_STATE` on its own whenever the call changes:
`State`, `Name`, `Address`, `Elapsed`, and `Flags` (bit 0 muted, bits 4-7 the
audio route, bit 8 hang-up only).

Conversations and messages come from the SMS table and from the MMS table,
where Google Messages also files RCS chats. Conversations with more than one
other person are left out. Android keeps one thread per notation of a number
("12.34.56", "+687123456"): the phone merges them into one conversation, which
goes by the id of its most recent thread, and any of them answers for it.

The launcher event is the latest missed call or received message of the last
24 hours, as picked in the phone settings. The watch writes it under the app
name in the launcher when the app closes.

A page is `Offset`, `Total` and `Items`, with a fingerprint of the page in
`Flags`. A request for offset 0 may carry the fingerprint of the page the watch
already holds in `Flags`: when it still matches, the reply holds `Status` and
`Flags` only, and the watch keeps its rows. The phone fills `Items` with as many
records as fit in the watch `Budget` and the watch asks for the next page when
it needs it.

### Photos

`PHOTO` asks for the photo of a contact by `Id`, or of the contact owning
`Address` when `Id` is 0. `Flags` holds the edge length in pixels in bits 0-11
and the format in bits 12-15.

With bit 28 of `Flags` set, it asks instead for the picture of the message `Id`
(as numbered in the `messages` list), scaled to the width in bits 0-11 and the
height in bits 16-27. A picture fills its whole rectangle: no circle, nothing
transparent.

| Format | Pixels                                                                 |
|--------|------------------------------------------------------------------------|
| 0      | One byte each, `0bAARRGGBB`, transparent outside the inscribed circle  |
| 1      | One bit each, 1 for white, least significant bit first, rows padded to whole bytes, white outside the circle |

The reply carries `Offset`, `Total` (bytes of the whole photo) and `Items` (the
bytes from `Offset`). The watch asks for the next offset until it has `Total`
bytes, and writes them straight into its bitmap. `Status` is 2 (not found) when
the contact has no photo.

### Record layout

All integers little endian. Strings are UTF-8, NUL terminated, already cut to
a length the watch can show.

```
u8   flags
i32  id
u32  time      unix seconds, 0 when meaningless
str  title
str  detail
str  extra
```

| List      | id          | time      | flags                          | title       | detail          | extra   |
|-----------|-------------|-----------|--------------------------------|-------------|-----------------|---------|
| contacts  | contact id  | 0         | 0                              | name        |                 |         |
| favorites | contact id  | 0         | 0                              | name        | main number label | main number |
| letters   | count       | 0         | 0                              | letter      | first names     |         |
| replies   | 0           | 0         | 0                              |             | reply text      |         |
| numbers   | 0           | 0         | 0                              | label       | number          |         |
| calls     | 0           | call date | call type (1 in, 2 out, 3 missed, 5 rejected) | name or number | number | |
| threads   | thread id   | last date | bit 0 unread                   | name        | last message    | number  |
| messages  | message id, negated for an MMS | date | bit 0 sent by me, bit 1 cut, bit 2 picture | sender | body | picture size, `<w>x<h>` |
| launcher event | 0      | date      | 1 missed call, 2 message       | name or number | start of the message |  |

### Capability flags (`HELLO`)

| Bit | Meaning                                                     |
|-----|-------------------------------------------------------------|
| 0   | Contacts readable                                           |
| 1   | Call log readable                                           |
| 2   | Calls can be placed                                         |
| 3   | SMS readable                                                |
| 4   | SMS can be sent                                             |
| 5   | In-call controls (companion watch association granted)      |

### Text sizes (`HELLO`)

`Size` sets the menus, `BodySize` the screen that shows a whole message. `1` to
`4` force small, medium, large or extra large. `0` follows the "Text size"
setting of the watch for menus, and for messages the default text size of
notifications (large on Time 2 and Round 2, medium elsewhere), since apps cannot
read the notification setting itself. Menus get the fonts and row heights of
the system menus at that size, messages the fonts of system notifications
(Gothic 28 bold stands in for Gothic 36, which apps cannot load). The watch
keeps the last values it received, so the first screen already has the right
size the next time the app opens.

### Status codes

| Code | Meaning                         |
|------|---------------------------------|
| 0    | OK                              |
| 1    | Permission missing on the phone |
| 2    | Not found                       |
| 3    | Failed                          |
| 4    | Pending: the radio has not reported on a `SEND_SMS` yet, the text may still leave |
