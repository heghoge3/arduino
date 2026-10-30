#include "inertial.h"
#include "spoof.h"
#include <math.h>

namespace inertial {
namespace {
constexpr float RAD = M_PI / 180.0f;
float wrap(float a) { return fmodf(a + 720, 360); }
float diff(float a, float b) { return fmodf(a - b + 540, 360) - 180; }
float norm(float x, float y, float z) { return sqrtf(x*x + y*y + z*z); }
void normalize(float q[4]) {
  float n = sqrtf(q[0]*q[0]+q[1]*q[1]+q[2]*q[2]+q[3]*q[3]);
  for (int i = 0; i < 4; ++i) q[i] /= n;
}
}
void Navigator::reset() { *this = Navigator(); }
bool Navigator::copyBias(float out[3]) const {
  if (!biasReady_) return false;
  for (int j=0;j<3;j++) out[j]=bias_[j];
  return true;
}
void Navigator::restoreBias(const float bias[3]) {
  for (int j=0;j<3;j++) if (!isfinite(bias[j]) || fabsf(bias[j])>35) return;
  for (int j=0;j<3;j++) bias_[j]=bias[j];
  biasReady_=true;
}

void Navigator::loseOrientation() {
  float saved[3]; bool known=copyBias(saved);
  reset(); if (known) restoreBias(saved);
}

void Navigator::feed(float ax, float ay, float az, float gx, float gy, float gz, uint32_t steps, uint32_t ms) {
  if (!isfinite(ax) || !isfinite(ay) || !isfinite(az) || !isfinite(gx) || !isfinite(gy) || !isfinite(gz) ||
      fabsf(gx) >= 490 || fabsf(gy) >= 490 || fabsf(gz) >= 490) {
    loseOrientation(); return; // потерянное/насыщенное вращение нельзя восстановить
  }
  float length = norm(ax, ay, az);
  bool goodAccel=length>=.2f && length<=2 && fabsf(ax)<1.99f && fabsf(ay)<1.99f && fabsf(az)<1.99f;
  // Через короткую задержку SD интегрируем последнее вращение как грубое приближение.
  if (init_ && (ms-lastMs_>1000 || steps<lastSteps_)) loseOrientation();
  if (!goodAccel && !init_) return;
  if (!init_) {
    init_ = true; lastMs_ = ms; lastSteps_ = steps;
    float x = ax/length, y = ay/length, z = az/length;
    if (z < -.999f) { q_[0]=0; q_[1]=1; q_[2]=q_[3]=0; }
    else { q_[0]=1+z; q_[1]=y; q_[2]=-x; q_[3]=0; normalize(q_); }
    previousA_[0]=smoothA_[0]=ax; previousA_[1]=smoothA_[1]=ay; previousA_[2]=smoothA_[2]=az;
    return;
  }
  uint32_t dtMs = ms-lastMs_; lastMs_ = ms;
  if (!dtMs) return;
  if (steps != lastSteps_) { lastStepAt_=ms; lastSteps_=steps; }

  // Смещение гироскопа измеряется автоматически во время короткого реального покоя.
  bool quiet = goodAccel && fabsf(length-1) < .035f && norm(ax-previousA_[0],ay-previousA_[1],az-previousA_[2]) < .012f &&
      norm(gx,gy,gz) < 35 && (!lastStepAt_ || ms-lastStepAt_ > 2500);
  float accel[] = {ax,ay,az}, gyro[] = {gx,gy,gz};
  for (int j=0;j<3;j++) previousA_[j]=accel[j];
  if (!quiet) { quietSince_=quietN_=0; for (float& v:quietSum_) v=0; for (float& v:quietSq_) v=0; }
  else {
    if (!quietN_) quietSince_=ms;
    for (int j=0;j<3;j++) { quietSum_[j]+=gyro[j]; quietSq_[j]+=gyro[j]*gyro[j]; }
    quietN_++;
    if (ms-quietSince_ >= 2000 && quietN_ >= 40) {
      float variance=0;
      for (int j=0;j<3;j++) variance+=fmaxf(0,quietSq_[j]/quietN_-powf(quietSum_[j]/quietN_,2));
      if (variance<.25f) {
        if (!biasReady_) {
          // Первичная большая заводская ошибка могла уже наклонить интегрированную ориентацию.
          float a=ax/length,b=ay/length,c=az/length;
          if (c<-.999f) { q_[0]=0; q_[1]=1; q_[2]=q_[3]=0; }
          else { q_[0]=1+c; q_[1]=b; q_[2]=-a; q_[3]=0; normalize(q_); }
          size_=at_=0; haveAxis_=aligned_=false;
        }
        for (int j=0;j<3;j++) bias_[j]=quietSum_[j]/quietN_;
        biasReady_=true;
      }
      quietSince_=quietN_=0; for (float& v:quietSum_) v=0; for (float& v:quietSq_) v=0;
    }
  }

  float dt=dtMs/1000.0f;
  float alpha=dt/(.45f+dt);
  if (goodAccel) for (int j=0;j<3;j++) smoothA_[j]+=alpha*(accel[j]-smoothA_[j]);
  float w=q_[0], x=q_[1], y=q_[2], z=q_[3];
  float wx=(gx-bias_[0])*RAD, wy=(gy-bias_[1])*RAD, wz=(gz-bias_[2])*RAD;
  // Mahony-подобная коррекция крена/тангажа низкочастотной гравитацией.
  // Магнитные оси отдельных приклеенных плат совмещать не требуется.
  float gnorm=norm(smoothA_[0],smoothA_[1],smoothA_[2]);
  if (goodAccel && fabsf(length-1)<.18f && gnorm>.8f && gnorm<1.2f) {
    float a=smoothA_[0]/gnorm,b=smoothA_[1]/gnorm,c=smoothA_[2]/gnorm;
    float vx=2*(x*z-w*y), vy=2*(w*x+y*z), vz=1-2*(x*x+y*y);
    wx+=.5f*(b*vz-c*vy); wy+=.5f*(c*vx-a*vz); wz+=.5f*(a*vy-b*vx);
  }
  q_[0]+=(-x*wx-y*wy-z*wz)*dt*.5f;
  q_[1]+=(w*wx+y*wz-z*wy)*dt*.5f;
  q_[2]+=(w*wy-x*wz+z*wx)*dt*.5f;
  q_[3]+=(w*wz+x*wy-y*wx)*dt*.5f;
  normalize(q_);
  w=q_[0]; x=q_[1]; y=q_[2]; z=q_[3];
  if (!goodAccel) return; // Удар не задаёт новую ось ходьбы; гироскоп продолжил работать.
  // Ускорение в неподвижной системе координат: поворот корпуса исключается из оси ходьбы.
  float horizontalX=(1-2*(y*y+z*z))*ax+2*(x*y-w*z)*ay+2*(x*z+w*y)*az;
  float horizontalY=2*(x*y+w*z)*ax+(1-2*(x*x+z*z))*ay+2*(y*z-w*x)*az;
  samples_[at_]={horizontalX,horizontalY,steps}; at_=(at_+1)%WINDOW;
  if (size_<WINDOW) size_++;
  if (ms-lastAxisCalc_>=200) { lastAxisCalc_=ms; calculateAxis(ms); }
}

void Navigator::calculateAxis(uint32_t ms) {
  if (!biasReady_ || size_<60 || !lastStepAt_ || ms-lastStepAt_>1800) return;
  unsigned oldest=size_==WINDOW ? at_ : 0;
  if (lastSteps_<samples_[oldest].steps || lastSteps_-samples_[oldest].steps<3) return;
  float mx=0,my=0,xx=0,xy=0,yy=0;
  for (unsigned i=0;i<size_;i++) { mx+=samples_[i].x; my+=samples_[i].y; }
  mx/=size_; my/=size_;
  for (unsigned i=0;i<size_;i++) {
    float x=samples_[i].x-mx,y=samples_[i].y-my; xx+=x*x; xy+=x*y; yy+=y*y;
  }
  xx/=size_; xy/=size_; yy/=size_;
  float spread=sqrtf((xx-yy)*(xx-yy)+4*xy*xy), major=(xx+yy+spread)*.5f, minor=(xx+yy-spread)*.5f;
  // Не приписываем направление вертикальной ходьбе или хаотичной тряске.
  if (major<.000625f || major<1.8f*fmaxf(minor,.00001f)) return;
  float candidate=wrap(-.5f*atan2f(2*xy,xx-yy)/RAD);
  if (haveAxis_ && fabsf(diff(candidate+180,axis_))<fabsf(diff(candidate,axis_))) candidate=wrap(candidate+180);
  // Сохраняем непрерывную ветвь оси; разворот различим, если были промежуточные направления.
  if (haveAxis_) axis_=wrap(axis_+.35f*diff(candidate,axis_)); else axis_=candidate;
  haveAxis_=true; axisAt_=ms;
}

bool Navigator::offerGps(double lat,double lon,uint32_t steps,uint32_t ms) {
  if (!isfinite(lat)||!isfinite(lon)||lat < -90||lat>90||lon < -180||lon>180) return false;
  if (!haveGps_ || ms-gpsAt_>20000 || steps<gpsSteps_) {
    haveGps_=true; gpsLat_=lat; gpsLon_=lon; gpsAt_=ms; gpsSteps_=steps; return false;
  }
  double distance=spoof::distanceM(gpsLat_,gpsLon_,lat,lon);
  if (distance<10) return false;
  bool useful=distance<=60 && ms-gpsAt_>=2000 && distance*1000/(ms-gpsAt_)<=3.5 && steps-gpsSteps_>=6 && distance/(steps-gpsSteps_)<=2 && biasReady_ && haveAxis_ && ms-axisAt_<=800;
  if (useful) {
    double a=gpsLat_*RAD,b=lat*RAD,d=(lon-gpsLon_)*RAD;
    float course=wrap(atan2(sin(d)*cos(b),cos(a)*sin(b)-sin(a)*cos(b)*cos(d))/RAD);
    float wanted=diff(course,axis_);
    // GPS задаёт знак неоднозначной оси и автоматически учитывает положение корпуса.
    if (!aligned_) offset_=wanted; else offset_=wrap(offset_+.25f*diff(wanted,offset_));
    aligned_=true; alignedAt_=ms;
  }
  gpsLat_=lat; gpsLon_=lon; gpsAt_=ms; gpsSteps_=steps;
  return useful;
}
float Navigator::heading(uint32_t ms) const {
  // Короткий невыразительный шаг переживаем по предыдущей оси; длительные пропуски не выдумываем.
  return aligned_ && biasReady_ && haveAxis_ && ms-lastMs_<=1000 && ms-axisAt_<=10000 ? wrap(axis_+offset_) : NAN;
}
bool Navigator::keepAwake(uint32_t ms) const { return aligned_ && lastStepAt_ && ms-lastStepAt_<300000; }
uint8_t Navigator::state(uint32_t ms) const {
  if (!biasReady_) return 15;
  if (!aligned_) return 14;
  if (ms-alignedAt_>20*60*1000) return 17;
  return isfinite(heading(ms)) ? 0 : 16;
}
}
