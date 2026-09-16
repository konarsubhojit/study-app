# Store listing

The text, graphics and declarations submitted to Google Play, kept in the repository so that a
review rejection is fixed by a pull request rather than by remembering what was typed into a form.
Issue [#71](https://github.com/konarsubhojit/study-app/issues/71).

Character limits are Google's and are enforced by the Console: app name 30, short description 80,
full description 4000.

## Text

**App name** (22/30)

```
StudyFlow: Study Timer
```

**Short description** (77/80)

```
Track study time with a timer that survives reboots. Materials and reminders.
```

**Full description**

```
StudyFlow is a study tracker built around one promise: the time you studied is the time it
records.

A TIMER THAT SURVIVES ANYTHING
Close the app, reboot the phone, let the battery die, cross a time zone or a daylight-saving
change — your session is still there and still correct. StudyFlow records what happened rather
than counting ticks, so a corrected clock cannot invent hours or erase real work. When the device
restarts mid-session it asks you about the gap instead of guessing.

YOUR STUDY MATERIALS, ANY FORMAT
Keep PDFs, slides, notes, images, recordings and archives beside the sessions they belong to.
Large files upload in parts and resume where they stopped, so a lost connection costs you a few
seconds instead of the whole upload. Pinned files stay available offline.

REMINDERS THAT ACTUALLY FIRE
Daily, weekly or custom repeats, scheduled so they arrive on time. "Every day at 08:00" stays at
08:00 across a daylight-saving change, and a reminder for the 31st is not silently skipped in a
shorter month. If Android withholds a permission StudyFlow needs, it tells you plainly instead of
failing in silence.

SEE YOUR PROGRESS
Daily and weekly totals, streaks and per-subject breakdowns, so a habit is something you can see
rather than something you hope you have.

BUILT WITH RESPECT FOR YOUR DEVICE
No ads. No tracking for advertising. Material 3 design with dynamic colour, full dark theme, and
accessibility support including TalkBack and large text. StudyFlow is open source:
https://github.com/konarsubhojit/study-app
```

Every claim above must hold for the build being submitted; Play treats a description of features
the app does not have as a misrepresentation. Trim any paragraph whose feature has not yet
shipped — the timer paragraph is the only one required for the first internal-testing listing.

## Graphics

| Asset | Requirement | Notes |
|---|---|---|
| App icon | 512 × 512 PNG, 32-bit, no alpha | Same mark as the adaptive launcher icon, on the brand background |
| Feature graphic | 1024 × 500 PNG or JPEG, no alpha | Wordmark plus running timer; no screenshot frames, no small print — Play crops it on some surfaces |
| Phone screenshots | 2–8, 16:9 or 9:16, each side 320–3840 px | Running timer · session history · materials list · reminder setup · weekly insights |
| 7-inch tablet screenshots | Up to 8, same limits | Required for the app to be listed as tablet-optimised |
| 10-inch tablet screenshots | Up to 8, same limits | Expanded two-pane layout |
| Foldable screenshots | Up to 8, same limits | Unfolded inner display |

Capture screenshots from a release build with demonstration data — never a real user's study
history — using the default device theme, and keep the same five screens in the same order across
every form factor so the listing reads consistently.

## Categorisation

| Field | Value |
|---|---|
| App or game | App |
| Category | Education |
| Tags | Study tools, Productivity, Time management |
| Contact email | Monitored maintainer address |
| Website | https://github.com/konarsubhojit/study-app |
| Privacy policy | Published URL, required because the app handles user content |

## Content rating questionnaire

Category *Reference, News, or Educational*. The answers below describe StudyFlow and should be
re-checked whenever a feature lands that changes one of them.

| Question | Answer |
|---|---|
| Violence, sexual content, profanity, controlled substances | No |
| Gambling or simulated gambling | No |
| User-generated content shared with other users | No — materials are private to the account |
| Users can communicate with each other | No |
| Shares the user's location | No |
| Allows purchases or contains ads | No |
| Collects personal information | Account identifier only, for sign-in and sync |

Expected outcome: PEGI 3 / ESRB Everyone / IARC equivalent.

## App content declarations

| Declaration | Answer |
|---|---|
| Privacy policy | Published URL |
| Ads | The app contains no ads |
| App access | All functionality available without special access; if sign-in is required for the submitted build, supply a demo account under *App access* |
| Target audience | 13+; not designed for children, so no Families policy obligations |
| Data safety | Collected: email address and user id (account management), study sessions and uploaded files (app functionality). Encrypted in transit. Deletable on request. Not shared with third parties and not used for advertising |
| Government apps | No |
| Financial features | None |
| Health | None |

## Before submitting

- [ ] Text matches the shipped feature set, with no unshipped claims.
- [ ] All graphics meet the size and format rules and contain no real personal data.
- [ ] Content rating questionnaire completed and the certificate issued.
- [ ] Data safety form matches what the app actually collects and transmits.
- [ ] Privacy policy URL resolves and describes the same data.
