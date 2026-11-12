#!/usr/bin/env python3
"""Compile-only power-profile guard checks; no device, flash writes or private files."""
from pathlib import Path
import subprocess
import tempfile


def main():
    root = Path(__file__).resolve().parents[1]
    cases = [
        ("primary slider GPIO3", ["EXPECT_SWITCH_PIN=3"], None),
        ("explicit legacy gesture", ["V2_POWER_PROFILE=0", "EXPECT_SWITCH_PIN=-1"], None),
        ("explicit bench uses default slider", ["V2_SLIDER_BENCH=1", "EXPECT_SWITCH_PIN=-1"], None),
        ("unassigned override", ["V2_POWER_SWITCH_PIN=-1"], "Slider GPIO is not assigned"),
        ("explicit GPIO3", ["V2_POWER_SWITCH_PIN=3", "EXPECT_SWITCH_PIN=3"], None),
    ]
    for pin in (0, 7, 14, 19, 20, 45):
        cases.append((f"reserved/conflicting GPIO{pin}",
                      ["V2_POWER_PROFILE=1", f"V2_POWER_SWITCH_PIN={pin}"],
                      "Slider GPIO conflicts with a reserved, inaccessible or occupied pin"))
    cases.append(("physical input in bench", ["V2_POWER_PROFILE=1", "V2_SLIDER_BENCH=1",
                                              "V2_POWER_SWITCH_PIN=3"],
                  "Bench profile must not read a real switch GPIO"))
    with tempfile.TemporaryDirectory(prefix="v2-power-profiles-") as directory:
        source = Path(directory) / "probe.cpp"
        source.write_text('#include "power_profile.h"\n'
                          'static_assert(PIN_LCD_BACKLIGHT==14,"Unexpected backlight pad");\n'
                          'static_assert(!v2ForbiddenPin(14),"Approved GPIO14 pad must be available");\n'
                          'static_assert(v2ForbiddenPin(15),"Other bottom pads must stay blocked");\n'
                          '#ifdef EXPECT_SWITCH_PIN\n'
                          'static_assert(PIN_POWER_SWITCH==EXPECT_SWITCH_PIN,"Unexpected switch input");\n'
                          '#endif\nint main(){return int(POWER_PROFILE);}\n')
        base = ["g++", "-std=c++17", f"-I{root / 'include'}", f"-I{root / 'lib/core'}",
                "-c", str(source), "-o", str(Path(directory) / "probe.o")]
        for name, flags, error in cases:
            result = subprocess.run(base + [f"-D{flag}" for flag in flags],
                                    capture_output=True, text=True)
            if (error is None and result.returncode) or (error is not None and
                    (not result.returncode or error not in result.stderr)):
                raise SystemExit(f"FAILED {name}:\n{result.stderr}")
            print(f"OK {name}: {'accepted' if error is None else 'rejected as required'}")
    print(f"{len(cases)} compile-only checks passed")


if __name__ == "__main__":
    main()
