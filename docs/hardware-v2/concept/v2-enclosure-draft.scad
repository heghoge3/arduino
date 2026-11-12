// V2 enclosure concept, 12.11.2026. All dimensions in mm.
// Select assembled, open, layout, base or lid in OpenSCAD Customizer.
view = "assembled"; // [assembled,open,layout,base,lid]

// Outer envelope chosen for this concept. It excludes the lug and controls.
width = 72;
length = 110;
depth = 36;
wall = 2.6;
floor = 3;
face = 2.8;
lid_depth = 7;
corner = 6;

// Seller dimensions (unmeasured). The button depth is treated conservatively
// as an envelope behind the face. Panel hardware and leads need rechecking.
lcd = [56, 44.6, 15.2];
lcd_active = [48.62, 24.3];
button_thread = 22;
button_flange = 25;
button_depth = 13.8;
slider_flange = [23.3, 7.4];
slider_mount_pitch = 19;
slider_depth = 12;
slider_handle = [5.5, 3.8, 5];
slider_travel = 3.2;
battery = [34, 50, 10];
gps_antenna = [20, 6, 3]; // Z estimated
converter = [26.6, 18.2, 6]; // Z estimated
esp = [18, 23.5, 5.5]; // Z estimated
rtc = [13.5, 13.6, 12]; // Z estimated
gps = [16, 13.5, 5]; // Z estimated, larger outline from seller

// Layout estimates. Replace these with measured envelopes, including leads.
lcd_hole_pitch = [51, 39];
carrier = [56, 45, 1.6];
charger = [17, 20, 5];
sd = [17, 18, 4];
ina = [15, 17, 4.5];
mpu = [14, 15, 4];
compass = [14, 12, 4];
bme = [11, 13, 5];
buzzer = [12, 12, 6];
usb_cutout = [11, 4.8];
led_diameter = 3;

/* [Hidden] */
$fn = 48;
eps = 0.04;
case_color = "DarkOrange";
split_z = depth - lid_depth;
center = width / 2;
lcd_y = length - 33;
button_y = 40;
button_offset = 16;
slider_y = 44;
slider_z = 15;
usb_x = center + 13;
usb_z = 24;
led_y = 17;
lid_screws = [[6.5, 6.5], [width-6.5, 6.5], [6.5, length-6.5], [width-6.5, length-6.5]];
board_z = 5;
module_z = board_z + carrier[2];
lcd_back = depth - lcd[2];

assert(width > lcd[0] + 2*wall, "LCD envelope exceeds case width");
assert(button_offset*2 > button_flange, "Button flanges overlap");
assert(button_y + button_flange/2 < lcd_y - lcd[1]/2, "Button row overlaps LCD envelope");
assert(module_z + rtc[2] < lcd_back, "RTC envelope reaches the LCD");

module rounded_plate(w, l, r, h) {
    hull() for (x = [r, w-r], y = [r, l-r])
        translate([x,y,0]) cylinder(r=r, h=h);
}

module centered_box(x, y, z, size) {
    translate([x-size[0]/2, y-size[1]/2, z]) cube(size);
}

module bore(x, y, z, d, h) {
    translate([x,y,z]) cylinder(d=d, h=h);
}

module lug() {
    difference() {
        hull() {
            translate([center-13,length-5,0]) cube([26,7,9]);
            bore(center,length+8,0,23,9);
        }
        bore(center,length+8,-eps,13,9+2*eps);
    }
}

module base() {
    difference() {
        union() {
            difference() {
                rounded_plate(width,length,corner,split_z);
                translate([wall,wall,floor])
                    rounded_plate(width-2*wall,length-2*wall,corner-wall,split_z-floor+eps);
            }
            lug();
            for (p = lid_screws) bore(p[0],p[1],floor-eps,8,split_z-floor+eps);
            // Closed partition in plan. Final membrane, wire feedthrough and seal follow measurements.
            translate([wall-eps,21,floor-eps]) cube([20-wall+eps,2.2,split_z-floor+eps]);
            translate([20,wall-eps,floor-eps]) cube([2.2,23.2-wall+eps,split_z-floor+eps]);
            // Carrier standoffs and two soft-pad zones under the battery.
            for (x = [10,width-10], y = [57,95]) bore(x,y,floor-eps,5,board_z-floor+eps);
            for (y = [10,48]) translate([26,y,floor-eps]) cube([30,4,1+eps]);
            // The converter bridge clears the battery; the USB module has its own ledge.
            translate([22,30,floor-eps]) cube([2,4,12+eps]);
            translate([59,30,floor-eps]) cube([2,4,12+eps]);
            translate([22,30,14]) cube([39,4,1]);
            translate([40.5,wall-eps,21]) cube([17,5,1]);
        }
        for (p = lid_screws) bore(p[0],p[1],split_z-5,4.2,5+eps);
        translate([usb_x-usb_cutout[0]/2,-eps,usb_z-usb_cutout[1]/2])
            cube([usb_cutout[0],wall+2*eps,usb_cutout[1]]);
        // Slider travel slot and two panel mounting pilots along the right wall.
        translate([width-wall-eps,slider_y-(slider_handle[0]+slider_travel+1)/2,slider_z-2.5])
            cube([wall+2*eps,slider_handle[0]+slider_travel+1,5]);
        for (y = [slider_y-slider_mount_pitch/2, slider_y+slider_mount_pitch/2])
            translate([width-wall-eps,y,slider_z]) rotate([0,90,0]) cylinder(d=2.2,h=wall+2*eps);
        for (y = [9,13,17])
            translate([-eps,y,9]) rotate([0,90,0]) cylinder(d=2.3,h=wall+2*eps);
    }
}

module lid() {
    difference() {
        union() {
            translate([0,0,depth-face]) rounded_plate(width,length,corner,face);
            translate([0,0,split_z]) difference() {
                rounded_plate(width,length,corner,lid_depth-face+eps);
                translate([wall,wall,-eps])
                    rounded_plate(width-2*wall,length-2*wall,corner-wall,lid_depth-face+3*eps);
            }
            // Front guard around the LCD. Only the perimeter contacts a flat surface.
            centered_box(center,lcd_y,depth-eps,[54,31,1.3+eps]);
            for (x = [-1,1], y = [-1,1])
                bore(center+x*lcd_hole_pitch[0]/2,lcd_y+y*lcd_hole_pitch[1]/2,lcd_back+1.6,5.2,depth-face-lcd_back-1.6+eps);
        }
        centered_box(center,lcd_y,depth-face-eps,[lcd_active[0]+0.8,lcd_active[1]+0.8,face+1.3+2*eps]);
        for (side = [-1,1]) {
            bore(center+side*button_offset,button_y,depth-face-eps,button_thread+0.4,face+2*eps);
            bore(center+side*4,led_y,depth-face-eps,led_diameter+0.25,face+2*eps);
        }
        for (p = lid_screws) {
            bore(p[0],p[1],depth-face-eps,3.3,face+2*eps);
            bore(p[0],p[1],depth-1.2,5.8,1.2+eps);
        }
        for (x = [-1,1], y = [-1,1])
            bore(center+x*lcd_hole_pitch[0]/2,lcd_y+y*lcd_hole_pitch[1]/2,lcd_back+1.6-eps,3.2,4.2);
    }
}

module front_hardware() {
    color("DimGray") difference() {
        centered_box(center,lcd_y,lcd_back,[lcd[0],lcd[1],lcd[2]-face]);
        for (x = [-1,1], y = [-1,1])
            bore(center+x*lcd_hole_pitch[0]/2,lcd_y+y*lcd_hole_pitch[1]/2,lcd_back+1.6,5.2+eps,lcd[2]);
    }
    color("DarkSlateGray") centered_box(center,lcd_y,depth-face-eps,[lcd_active[0],lcd_active[1],face+eps]);
    for (side = [-1,1]) {
        color("Silver") bore(center+side*button_offset,button_y,depth-button_depth,button_thread-0.1,button_depth);
        color("Silver") bore(center+side*button_offset,button_y,depth,button_flange,1.6);
        color(side<0 ? "FireBrick" : "ForestGreen") bore(center+side*4,led_y,depth-1,led_diameter,2.3);
    }
}

module side_hardware() {
    color("DimGray") translate([width-slider_depth,slider_y-slider_flange[0]/2,slider_z-slider_flange[1]/2])
        cube([slider_depth-wall,slider_flange[0],slider_flange[1]]);
    color("Black") translate([width-wall,slider_y-slider_handle[0]/2,slider_z-slider_handle[1]/2])
        cube([wall+slider_handle[2],slider_handle[0],slider_handle[1]]);
    color("SlateGray") centered_box(usb_x,1.4,usb_z-1.6,[9,2.4,3.2]);
}

module inside() {
    color("SlateGray") centered_box(41,29,4,battery);
    color("Ivory") centered_box(60,52,7,[6,5,4]); // service access to battery JST
    color("SeaGreen") centered_box(center,77,board_z,carrier);
    color("RoyalBlue") {
        centered_box(19,85,module_z,esp);
        centered_box(37,90.5,module_z,gps);
        centered_box(53,91.5,module_z,rtc);
        centered_box(18,64,module_z,sd);
        centered_box(36,63,module_z,ina);
        centered_box(55,63,module_z,mpu);
        centered_box(55,78.5,module_z,compass);
        centered_box(usb_x,wall+charger[1]/2,usb_z-2,charger);
        centered_box(40,25,15,converter);
    }
    color("Teal") centered_box(14,14,6,bme);
    color("Black") centered_box(13,40,6,buzzer);
    color("Peru") centered_box(center,103,7,gps_antenna);
}

if (view == "base") base();
if (view == "lid") translate([0,0,-split_z]) lid();
if (view == "assembled" || view == "open" || view == "layout") {
    color(case_color) base();
    side_hardware();
    inside();
    if (view == "assembled") {
        color(case_color) lid();
        front_hardware();
    }
    if (view == "open") translate([width+14,0,0]) {
        color(case_color,0.62) lid();
        front_hardware();
    }
}
