#!/usr/bin/env python3
"""Synthesizes the demo video's background pad, standard library only.

Nothing licensed, nothing downloaded: a quiet four-chord pad (Fmaj7, Am7,
Dm7, G6) built from detuned sine pairs with slow envelopes, so the recording
stays fully reproducible from the repository. Written as 16-bit stereo WAV.

Usage: demo-music.py DURATION_SECONDS OUTPUT.wav
"""

import math
import struct
import sys
import wave

RATE = 44100
CHORD_SECONDS = 4.5
ATTACK = 1.4
TAIL = 1.6
PEAK = 0.13

# Note frequencies (Hz), voiced in a comfortable mid register.
F3, A3, C4, E4 = 174.61, 220.00, 261.63, 329.63
D3, G3, B3, D4, G4 = 146.83, 196.00, 246.94, 293.66, 392.00
CHORDS = [
	(87.31, [F3, A3, C4, E4]),   # Fmaj7 over F2
	(110.00, [A3, C4, E4, G4]),  # Am7 over A2
	(73.42, [D3, F3, A3, C4]),   # Dm7 over D2
	(98.00, [G3, B3, D4, E4]),   # G6 over G2
]


def main():
	duration = float(sys.argv[1])
	out_path = sys.argv[2]
	total = int(duration * RATE)
	left = [0.0] * total
	right = [0.0] * total

	chord_index = 0
	start = 0.0
	while start < duration:
		bass, notes = CHORDS[chord_index % len(CHORDS)]
		begin = int(start * RATE)
		end = min(total, int((start + CHORD_SECONDS + TAIL) * RATE))
		length = end - begin
		for base in [bass] + notes:
			# A detuned pair per note reads as one soft, wide voice.
			for detune, channel in [(0.9988, left), (1.0012, right)]:
				freq = base * detune
				gain = (0.55 if base == bass else 0.3) / len(notes)
				step = 2.0 * math.pi * freq / RATE
				phase = 0.0
				for i in range(length):
					t = i / RATE
					if t < ATTACK:
						env = t / ATTACK
					elif t > CHORD_SECONDS:
						env = max(0.0, 1.0 - (t - CHORD_SECONDS) / TAIL)
					else:
						env = 1.0
					channel[begin + i] += gain * env * math.sin(phase)
					phase += step
		chord_index += 1
		start += CHORD_SECONDS

	frames = bytearray()
	for i in range(total):
		t = i / RATE
		# Slow breathing, plus a fade-in and a fade-out at the video's end.
		lfo = 1.0 + 0.12 * math.sin(2.0 * math.pi * 0.05 * t)
		fade = min(1.0, t / 2.0, max(0.0, (duration - t) / 3.0))
		for channel in (left, right):
			value = max(-1.0, min(1.0, channel[i] * PEAK * lfo * fade))
			frames += struct.pack('<h', int(value * 32767))

	with wave.open(out_path, 'wb') as f:
		f.setnchannels(2)
		f.setsampwidth(2)
		f.setframerate(RATE)
		f.writeframes(bytes(frames))


if __name__ == '__main__':
	main()
