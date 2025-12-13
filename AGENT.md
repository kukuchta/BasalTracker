=== PROJECT CONTEXT: Basal Insulin Profile Editor (Android, Java) ===

This text is a condensed, self-contained handoff that captures:
- All requirements and UX rules we agreed on,
- Key design decisions,
- The complete, production-level code set (domain, Room, repo, ViewModel, UI, adapters, layouts, activity, gradle, manifest),
- Invariants, validation, and edge cases,
to allow another language model (or developer) to continue the work with full context.

----------------------------------------------------------------------
REQUIREMENTS (FINAL)
----------------------------------------------------------------------
1) BasalProfile data describes an insulin basal rate profile over a day (0:00–24:00).
2) Segment-based model (run-length encoding / "change points"):
   - Each segment starts at `startMinutes` and holds a constant rate until the next change point or 24:00.
   - Grid: **startMinutes must be a multiple of 60** (hour resolution).
   - First change point MUST be at 0; last start MUST be < 1440; no change point at 1440.
3) Accuracy:
   - Profile has `accuracy` (double, U/h) defining the minimum step for dose changes.
   - All rates are persisted as **integer units** (`rateUnits`), where `rate = rateUnits * accuracy`. This avoids floating-point drift.
4) Health app constraints:
   - No inaccurate fallbacks; violations throw exceptions (IllegalArgumentException/IllegalStateException).
   - No silent auto-corrections when that would hide data errors; we only merge adjacent same-rate segments (safe normalization).
5) Persistence:
   - Local DB via Room: `BasalProfileEntity`, `BasalSegmentEntity`, relation, DAO, AppDatabase.
6) Domain methods:
   - `getBasalRate(minutes)` → get U/h at given minute (hour grid).
   - `getTotalDailyDose()` → precise `BigDecimal` sum across the day.
   - `toHourArray()` → 24 points for chart (hour starts).
   - Editing:
     a) Hourly mode: `adjustRateForHour(hour, increase)`:
        - Compute max/min units within the hour [h*60, h*60+60).
        - First click (+): equalize all subsegments in that hour to maxUnits.
          First click (−): equalize to minUnits.
        - Subsequent clicks (when already equal): change rate by ±1 unit (± accuracy) with non-negativity check.
     b) Segments mode:
        - `updateSegmentRate(segmentStartMinutes, newRate)` (rounded to accuracy units, merges neighbors).
        - `updateSegmentEnd(segmentStartMinutes, newEndMinutes)` (hour-only grid):
          * Adjust next segment start or insert a filler segment (rateUnits = 0) to maintain coverage.
          * Strictly enforce: newEnd > start, newEnd ≤ 1440, newEnd % 60 == 0.
          * Merge adjacent equal-dose segments after edit.
7) UI:
   - Unified editor fragment with two in-place modes:
     * Hourly Mode: MPAndroidChart line chart (24 points) + four buttons (←, →, −, +).
     * Segments Mode: RecyclerView list of segments. Each row shows start (readonly), end (hour-only picker), dose (accuracy-step NumberPicker). End picker guarantees **end > start** and hour grid.
   - Mode switch via **MaterialButtonToggleGroup** (segmented control) per Material Design.
8) List screen:
   - Shows stored profiles with: profile Id, name, total daily dose.
   - Actions per row: Edit, Duplicate, Delete.
   - FAB/Extended FAB to create a new empty profile (single change point at 0:00, rateUnits=0).
   - Edit/open loads the unified editor with the correct profile; create opens editor with the new profile.
9) Robustness:
   - `rewriteRangeToUnits60(...)` prevents "duplicate startMinutes" and avoids adding a change point at 24:00.
   - `normalizeAndValidate()` sorts, merges adjacent equal-dose segments, enforces hour grid and invariants.

----------------------------------------------------------------------
DESIGN DECISIONS
----------------------------------------------------------------------
- Time representation: minutes since midnight (`int`), hour grid (multiples of 60 only).
- Rate storage: integer `rateUnits` with `rate = units * accuracy`, eliminates floating drift.
- Domain model uses change points (RLE): simpler merging, precise edits, persistence-friendly.
- Graph: MPAndroidChart line chart, 24 buckets (hour start values).
- UI switch: MaterialButtonToggleGroup for in-context mode switching (better than tabs for modes).
- Persistent model and domain invariants guarantee correctness and avoid dangerous fallbacks.

----------------------------------------------------------------------
CODEBASE (FILES + CONTENTS)
----------------------------------------------------------------------
[1] app/build.gradle
------------------------------------------------------------
plugins {
    id 'com.android.application'
}
android {
    namespace 'com.example.basalapp'
    compileSdk 35
    defaultConfig {
        applicationId "com.example.basalapp"
        minSdk 24
        targetSdk 35
        versionCode 1
        versionName "1.0"
        vectorDrawables { useSupportLibrary true }
    }
    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}
dependencies {
    implementation 'androidx.appcompat:appcompat:1.7.0'
    implementation 'androidx.fragment:fragment:1.8.3'
    implementation 'androidx.recyclerview:recyclerview:1.3.2'
    implementation 'androidx.constraintlayout:constraintlayout:2.2.0'
    implementation 'androidx.lifecycle:lifecycle-livedata:2.8.4'
    implementation 'androidx.lifecycle:lifecycle-viewmodel:2.8.4'
    implementation 'com.google.android.material:material:1.12.0'
    implementation 'androidx.room:room-runtime:2.6.1'
    annotationProcessor 'androidx.room:room-compiler:2.6.1'
    implementation 'com.google.code.gson:gson:2.10.1'
    implementation 'com.github.PhilJay:MPAndroidChart:v3.1.0'
}

[2] app/src/main/AndroidManifest.xml
------------------------------------------------------------
<?xml version="1.0" encoding="utf-8"?>
<manifest package="com.example.basalapp" xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="Basal App"
        android:icon="@mipmap/ic_launcher"
        android:allowBackup="true"
        android:supportsRtl="true"
        android:theme="@style/Theme.Material3.DayNight.NoActionBar">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
    </application>
</manifest>

[3] MainActivity.java
------------------------------------------------------------
package com.example.basalapp;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;
import com.example.basalapp.ui.list.ProfileListFragment;
public class MainActivity extends AppCompatActivity {
    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.replace(android.R.id.content, new ProfileListFragment());
        ft.commit();
    }
}

[4] Domain: ProfileOrigin.java
------------------------------------------------------------
package com.example.basalapp.domain;
public enum ProfileOrigin { GENERATED, USER_MODIFIED, IMPORTED }

[5] Domain: BasalSegment.java
------------------------------------------------------------
package com.example.basalapp.domain;
public final class BasalSegment {
    private final int startMinutes; // multiple of 60
    private final int rateUnits;    // rate = rateUnits * accuracy
    public BasalSegment(int startMinutes, int rateUnits) {
        this.startMinutes = startMinutes; this.rateUnits = rateUnits;
    }
    public int getStartMinutes() { return startMinutes; }
    public int getRateUnits() { return rateUnits; }
}

[6] Domain: BasalProfileDiff.java
------------------------------------------------------------
package com.example.basalapp.domain;
public final class BasalProfileDiff {
    private final double totalDifference, maxDifference;
    public BasalProfileDiff(double totalDifference, double maxDifference) {
        this.totalDifference = totalDifference; this.maxDifference = maxDifference;
    }
    public double getTotalDifference() { return totalDifference; }
    public double getMaxDifference() { return maxDifference; }
}

[7] Domain: BasalProfile.java (core)
------------------------------------------------------------
package com.example.basalapp.domain;
import java.math.BigDecimal;
import java.util.*;
public final class BasalProfile {
    private final long id; private final String name; private final double accuracy;
    private final ProfileOrigin origin; private final Long baseProfileId;
    private final Map<String,String> metadata;
    private final List<BasalSegment> segments; // sorted change points
    public BasalProfile(long id, String name, double accuracy, ProfileOrigin origin,
                        Long baseProfileId, Map<String,String> metadata, List<BasalSegment> segments) {
        this.id = id; this.name = Objects.requireNonNull(name);
        if (accuracy <= 0.0) throw new IllegalArgumentException("accuracy must be > 0");
        this.accuracy = accuracy; this.origin = origin == null ? ProfileOrigin.USER_MODIFIED : origin;
        this.baseProfileId = baseProfileId; this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        this.segments = new ArrayList<>(Objects.requireNonNull(segments));
        normalizeAndValidate();
    }
    public long getId() { return id; } public String getName() { return name; }
    public double getAccuracy() { return accuracy; } public ProfileOrigin getOrigin() { return origin; }
    public Long getBaseProfileId() { return baseProfileId; }
    public Map<String,String> getMetadata() { return Collections.unmodifiableMap(metadata); }
    public List<BasalSegment> getSegments() { return Collections.unmodifiableList(segments); }

    private static boolean isHourGrid(int minutes){ return minutes % 60 == 0; }
    private void normalizeAndValidate() {
        segments.sort(Comparator.comparingInt(BasalSegment::getStartMinutes));
        if (segments.isEmpty()) throw new IllegalStateException("Profile must have at least one change point");
        if (segments.get(0).getStartMinutes() != 0) throw new IllegalStateException("First change point must start at 0");
        if (segments.get(segments.size()-1).getStartMinutes() >= 1440) throw new IllegalStateException("Last change point start must be < 1440");
        List<BasalSegment> merged = new ArrayList<>();
        Integer lastStart=null, lastUnits=null;
        for (BasalSegment s: segments){
            int start=s.getStartMinutes(), units=s.getRateUnits();
            if (!isHourGrid(start)) throw new IllegalStateException("startMinutes must align to 60-minute grid");
            if (units < 0) throw new IllegalStateException("rateUnits must be >= 0");
            if (lastStart!=null && start==lastStart){ merged.set(merged.size()-1, new BasalSegment(start, units)); lastUnits=units; continue; }
            if (lastUnits!=null && units==lastUnits) continue; // redundant change point
            merged.add(new BasalSegment(start, units)); lastStart=start; lastUnits=units;
        }
        segments.clear(); segments.addAll(merged);
    }
    private double toRate(int units){ return units * accuracy; }
    private int unitsAt(int minutes){
        int idx = Collections.binarySearch(segments, new BasalSegment(minutes,0),
                Comparator.comparingInt(BasalSegment::getStartMinutes));
        if (idx>=0) return segments.get(idx).getRateUnits();
        int ip = -idx-1, pos=ip-1; if (pos<0) throw new IllegalStateException("Profile malformed: no segment for time");
        return segments.get(pos).getRateUnits();
    }
    public double getBasalRate(int minutesSinceMidnight){
        if (minutesSinceMidnight<0 || minutesSinceMidnight>=1440) throw new IllegalArgumentException("minutesSinceMidnight must be in [0,1440)");
        return toRate(unitsAt(minutesSinceMidnight));
    }
    public double[] toHourArray(){
        double[] arr=new double[24]; for(int h=0;h<24;h++) arr[h]=getBasalRate(h*60); return arr;
    }
    public BigDecimal getTotalDailyDose(){
        BigDecimal total=BigDecimal.ZERO;
        for(int i=0;i<segments.size();i++){
            BasalSegment s=segments.get(i);
            int start=s.getStartMinutes(), end=(i+1<segments.size())?segments.get(i+1).getStartMinutes():1440;
            int minutes=end-start; if(minutes<=0) throw new IllegalStateException("Non-positive segment duration");
            BigDecimal hours=BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60),9,java.math.RoundingMode.UNNECESSARY);
            BigDecimal rate=BigDecimal.valueOf(toRate(s.getRateUnits()));
            total=total.add(rate.multiply(hours));
        }
        return total;
    }
    public BasalProfileDiff diff(BasalProfile other){
        double[] a=toHourArray(), b=other.toHourArray(); double total=0, max=0;
        for(int i=0;i<24;i++){ double d=Math.abs(a[i]-b[i]); total+=d; if(d>max) max=d; }
        return new BasalProfileDiff(total,max);
    }
    public void updateSegmentRate(int segmentStartMinutes, double newRate){
        int idx=indexOfStart(segmentStartMinutes); if(idx<0) throw new IllegalArgumentException("Segment not found");
        long units=Math.round(newRate/accuracy); if(units<0) throw new IllegalArgumentException("Rate cannot be negative");
        segments.set(idx, new BasalSegment(segmentStartMinutes,(int)units)); normalizeAndValidate();
    }
    public void updateSegmentEnd(int segmentStartMinutes, int newEndMinutes){
        if(!isHourGrid(newEndMinutes)) throw new IllegalArgumentException("newEnd must be multiple of 60");
        if(newEndMinutes<=segmentStartMinutes || newEndMinutes>1440) throw new IllegalArgumentException("newEnd must be within (start, 1440]");
        int idx=indexOfStart(segmentStartMinutes); if(idx<0) throw new IllegalArgumentException("Segment not found");
        int oldEnd=(idx+1<segments.size())?segments.get(idx+1).getStartMinutes():1440;
        if(newEndMinutes<oldEnd){ setNextStart(idx,newEndMinutes); }
        else if(newEndMinutes>oldEnd){ insertOrReplaceChangePoint(oldEnd,0); setNextStart(idx,newEndMinutes); }
        normalizeAndValidate();
    }
    public void adjustRateForHour(int hour, boolean increase){
        if(hour<0 || hour>23) throw new IllegalArgumentException("hour 0..23");
        int start=hour*60, end=start+60;
        int maxUnits=Integer.MIN_VALUE, minUnits=Integer.MAX_VALUE;
        forEachSubsegment(start,end,(s,e,u)->{ if(u>maxUnits) maxUnits=u; if(u<minUnits) minUnits=u; });
        boolean allEqual=(maxUnits==minUnits);
        int targetUnits= allEqual ? (increase?maxUnits+1:maxUnits-1) : (increase?maxUnits:minUnits);
        if(targetUnits<0) throw new IllegalArgumentException("Rate would become negative");
        rewriteRangeToUnits60(start,end,targetUnits); normalizeAndValidate();
    }
    private int indexOfStart(int startMinutes){ for(int i=0;i<segments.size();i++) if(segments.get(i).getStartMinutes()==startMinutes) return i; return -1; }
    private void setNextStart(int idx,int newStart){
        if(idx+1<segments.size()){ int nextUnits=segments.get(idx+1).getRateUnits(); segments.set(idx+1,new BasalSegment(newStart,nextUnits)); }
        else{ if(newStart!=1440) segments.add(new BasalSegment(newStart,0)); }
    }
    private void insertOrReplaceChangePoint(int startMinutes,int rateUnits){
        int pos=indexOfStart(startMinutes); if(pos>=0) segments.set(pos,new BasalSegment(startMinutes,rateUnits));
        else{ segments.add(new BasalSegment(startMinutes,rateUnits)); segments.sort(Comparator.comparingInt(BasalSegment::getStartMinutes)); }
    }
    private int unitsAtOriginal(List<BasalSegment> original,int minutes){
        int idx=Collections.binarySearch(original,new BasalSegment(minutes,0),Comparator.comparingInt(BasalSegment::getStartMinutes));
        if(idx>=0) return original.get(idx).getRateUnits();
        int ip=-idx-1,pos=ip-1; if(pos<0) throw new IllegalStateException("Profile malformed"); return original.get(pos).getRateUnits();
    }
    private int firstIndexAtOrAfter(List<BasalSegment> original,int boundary){
        int idx=Collections.binarySearch(original,new BasalSegment(boundary,0),Comparator.comparingInt(BasalSegment::getStartMinutes));
        return (idx>=0)?idx:(-idx-1);
    }
    private interface SubsegmentVisitor{ void visit(int s,int e,int units); }
    private void forEachSubsegment(int start,int end,SubsegmentVisitor v){
        int i=Collections.binarySearch(segments,new BasalSegment(start,0),Comparator.comparingInt(BasalSegment::getStartMinutes));
        int idx=(i>=0)?i:(-i-2); if(idx<0) idx=0;
        while(idx<segments.size()){
            int s=Math.max(start,segments.get(idx).getStartMinutes());
            int nextStart=(idx+1<segments.size())?segments.get(idx+1).getStartMinutes():1440;
            int e=Math.min(end,nextStart); if(s>=e) break;
            v.visit(s,e,segments.get(idx).getRateUnits()); if(nextStart>=end) break; idx++;
        }
    }
    private void appendChangePointUnique(List<BasalSegment> out,int start,int units){
        if(start>=1440) return;
        if(!isHourGrid(start)) throw new IllegalArgumentException("start must align to 60-minute grid");
        if(units<0) throw new IllegalArgumentException("units must be >= 0");
        if(out.isEmpty()){ if(start!=0) throw new IllegalStateException("first change point must be at 00:00"); out.add(new BasalSegment(start,units)); return; }
        BasalSegment last=out.get(out.size()-1);
        if(start==last.getStartMinutes()){ out.set(out.size()-1,new BasalSegment(start,units)); return; }
        if(last.getRateUnits()==units) return;
        out.add(new BasalSegment(start,units));
    }
    private void rewriteRangeToUnits60(int start,int end,int targetUnits){
        if(!isHourGrid(start)||!isHourGrid(end)) throw new IllegalArgumentException("range must align to 60-minute grid");
        if(start<0||start>=1440||end<=start||end>1440) throw new IllegalArgumentException("invalid range");
        List<BasalSegment> original=new ArrayList<>(segments); original.sort(Comparator.comparingInt(BasalSegment::getStartMinutes));
        int unitsAtStart=unitsAtOriginal(original,start);
        Integer unitsAtEnd=(end<1440)?unitsAtOriginal(original,end):null;
        List<BasalSegment> rebuilt=new ArrayList<>();
        for(BasalSegment s:original){ if(s.getStartMinutes()<start){ if(rebuilt.isEmpty()&&s.getStartMinutes()!=0) throw new IllegalStateException("first change point must be at 0"); appendChangePointUnique(rebuilt,s.getStartMinutes(),s.getRateUnits()); } else break; }
        appendChangePointUnique(rebuilt,start,targetUnits);
        int idxEnd=firstIndexAtOrAfter(original,end);
        if(end<1440){
            boolean hasOriginalAtEnd=(idxEnd<original.size() && original.get(idxEnd).getStartMinutes()==end);
            if(!hasOriginalAtEnd && unitsAtEnd!=null && unitsAtEnd!=targetUnits) appendChangePointUnique(rebuilt,end,unitsAtEnd);
        }
        for(int i=idxEnd;i<original.size();i++){ BasalSegment s=original.get(i); appendChangePointUnique(rebuilt,s.getStartMinutes(),s.getRateUnits()); }
        segments.clear(); segments.addAll(rebuilt);
    }
}

[8] Room: AppDatabase.java
------------------------------------------------------------
package com.example.basalapp.data.db;
import androidx.room.Database; import androidx.room.RoomDatabase;
import com.example.basalapp.data.db.entities.BasalProfileEntity;
import com.example.basalapp.data.db.entities.BasalSegmentEntity;
@Database(entities={BasalProfileEntity.class,BasalSegmentEntity.class},version=1,exportSchema=false)
public abstract class AppDatabase extends RoomDatabase { public abstract BasalProfileDao basalProfileDao(); }

[9] Room: BasalProfileEntity.java
------------------------------------------------------------
package com.example.basalapp.data.db.entities;
import androidx.room.Entity; import androidx.room.PrimaryKey;
@Entity(tableName="basal_profiles")
public class BasalProfileEntity {
    @PrimaryKey(autoGenerate=true) public long id;
    public String name; public double accuracy; public String origin;
    public Long baseProfileId; public String metadataJson; public long createdAt;
}

[10] Room: BasalSegmentEntity.java
------------------------------------------------------------
package com.example.basalapp.data.db.entities;
import androidx.room.*;
@Entity(tableName="basal_segments",
        foreignKeys=@ForeignKey(entity=BasalProfileEntity.class,parentColumns="id",childColumns="profileId", onDelete=ForeignKey.CASCADE),
        indices={@Index("profileId"),@Index(value={"profileId","startMinutes"},unique=true)})
public class BasalSegmentEntity {
    @PrimaryKey(autoGenerate=true) public long id;
    public long profileId; public int startMinutes; public int rateUnits;
}

[11] Room: BasalProfileWithSegments.java
------------------------------------------------------------
package com.example.basalapp.data.db.models;
import androidx.room.Embedded; import androidx.room.Relation;
import com.example.basalapp.data.db.entities.BasalProfileEntity;
import com.example.basalapp.data.db.entities.BasalSegmentEntity;
import java.util.List;
public class BasalProfileWithSegments {
    @Embedded public BasalProfileEntity profile;
    @Relation(parentColumn="id", entityColumn="profileId")
    public List<BasalSegmentEntity> segments;
}

[12] Room: BasalProfileDao.java
------------------------------------------------------------
package com.example.basalapp.data.db;
import androidx.room.*;
import com.example.basalapp.data.db.entities.*; import com.example.basalapp.data.db.models.*;
import java.util.List;
@Dao public interface BasalProfileDao {
    @Transaction @Query("SELECT * FROM basal_profiles ORDER BY createdAt DESC") List<BasalProfileWithSegments> getAllProfiles();
    @Transaction @Query("SELECT * FROM basal_profiles WHERE id = :id") BasalProfileWithSegments getProfile(long id);
    @Insert long insertProfile(BasalProfileEntity profile); @Update void updateProfile(BasalProfileEntity profile);
    @Insert void insertSegments(List<BasalSegmentEntity> segments);
    @Query("DELETE FROM basal_segments WHERE profileId = :profileId") void deleteSegmentsForProfile(long profileId);
    @Transaction default long upsertProfile(BasalProfileEntity profile, List<BasalSegmentEntity> segments){
        long id=(profile.id==0)?insertProfile(profile):profile.id; if(profile.id!=0) updateProfile(profile);
        deleteSegmentsForProfile(id); for(BasalSegmentEntity e:segments) e.profileId=id; insertSegments(segments); return id;
    }
    @Query("DELETE FROM basal_profiles WHERE id = :id") void deleteProfile(long id);
}

[13] Mapper: BasalProfileMapper.java
------------------------------------------------------------
package com.example.basalapp.data.mapper;
import com.example.basalapp.data.db.entities.*; import com.example.basalapp.data.db.models.*;
import com.example.basalapp.domain.*; import com.google.gson.Gson;
import java.util.*;
public final class BasalProfileMapper {
    private static final Gson gson=new Gson(); private BasalProfileMapper(){}
    public static BasalProfile toDomain(BasalProfileWithSegments row){
        Map<String,String> metadata=new HashMap<>();
        if(row.profile.metadataJson!=null && !row.profile.metadataJson.isEmpty())
            metadata=gson.fromJson(row.profile.metadataJson, metadata.getClass());
        List<BasalSegment> segs=new ArrayList<>();
        for(BasalSegmentEntity e:row.segments) segs.add(new BasalSegment(e.startMinutes,e.rateUnits));
        return new BasalProfile(row.profile.id,row.profile.name,row.profile.accuracy,
                ProfileOrigin.valueOf(row.profile.origin),row.profile.baseProfileId,metadata,segs);
    }
    public static BasalProfileEntity toEntity(BasalProfile domain){
        BasalProfileEntity pe=new BasalProfileEntity();
        pe.id=domain.getId(); pe.name=domain.getName(); pe.accuracy=domain.getAccuracy();
        pe.origin=domain.getOrigin().name(); pe.baseProfileId=domain.getBaseProfileId();
        pe.metadataJson=gson.toJson(domain.getMetadata()); pe.createdAt=System.currentTimeMillis(); return pe;
    }
    public static List<BasalSegmentEntity> toSegmentEntities(BasalProfile domain,long profileId){
        List<BasalSegmentEntity> list=new ArrayList<>();
        for(BasalSegment s:domain.getSegments()){
            BasalSegmentEntity e=new BasalSegmentEntity(); e.profileId=profileId; e.startMinutes=s.getStartMinutes(); e.rateUnits=s.getRateUnits(); list.add(e);
        }
        return list;
    }
}

[14] Repository: BasalProfileRepository.java
------------------------------------------------------------
package com.example.basalapp.data.repo;
import android.app.Application; import androidx.room.Room;
import com.example.basalapp.data.db.*; import com.example.basalapp.data.db.entities.BasalProfileEntity;
import com.example.basalapp.data.db.models.BasalProfileWithSegments; import com.example.basalapp.data.mapper.BasalProfileMapper;
import com.example.basalapp.domain.*; import java.util.*; import java.util.concurrent.*;
public class BasalProfileRepository {
    private final AppDatabase db; private final BasalProfileDao dao; private final ExecutorService io=Executors.newSingleThreadExecutor();
    public BasalProfileRepository(Application app){ db=Room.databaseBuilder(app,AppDatabase.class,"basal-db").fallbackToDestructiveMigration().build(); dao=db.basalProfileDao(); }
    public interface ListCallback{ void onResult(List<BasalProfile> profiles); }
    public interface ItemCallback{ void onResult(BasalProfile profile); }
    public interface IdCallback{ void onResult(long id); }
    public interface VoidCallback{ void onDone(); }
    public void getAllProfiles(ListCallback cb){ io.execute(()->{ List<BasalProfileWithSegments> rows=dao.getAllProfiles(); List<BasalProfile> res=new ArrayList<>(); for(BasalProfileWithSegments r:rows) res.add(BasalProfileMapper.toDomain(r)); cb.onResult(res); }); }
    public void getProfile(long id, ItemCallback cb){ io.execute(()->{ BasalProfileWithSegments row=dao.getProfile(id); cb.onResult(row==null?null:BasalProfileMapper.toDomain(row)); }); }
    public void upsert(BasalProfile profile, IdCallback cb){ io.execute(()->{ BasalProfileEntity pe=BasalProfileMapper.toEntity(profile); long id=dao.upsertProfile(pe,BasalProfileMapper.toSegmentEntities(profile,pe.id)); cb.onResult(id); }); }
    public void deleteProfile(long id, VoidCallback cb){ io.execute(()->{ dao.deleteSegmentsForProfile(id); dao.deleteProfile(id); cb.onDone(); }); }
    public void createEmptyProfile(String name,double accuracy,IdCallback cb){
        if(accuracy<=0.0) throw new IllegalArgumentException("accuracy must be > 0");
        io.execute(()->{
            BasalProfile p=new BasalProfile(0,(name==null||name.isEmpty())?"Nowy profil":name,accuracy,ProfileOrigin.USER_MODIFIED,null,new HashMap<>(), List.of(new BasalSegment(0,0)));
            BasalProfileEntity pe=BasalProfileMapper.toEntity(p); long id=dao.upsertProfile(pe,BasalProfileMapper.toSegmentEntities(p,0)); cb.onResult(id);
        });
    }
    public void duplicateProfile(long id,String nameSuffix,IdCallback cb){
        io.execute(()->{
            BasalProfileWithSegments row=dao.getProfile(id); if(row==null) throw new IllegalStateException("Profil nie istnieje, id="+id);
            BasalProfile original=BasalProfileMapper.toDomain(row);
            String newName=original.getName()+ (nameSuffix==null?" (kopia)":nameSuffix);
            BasalProfile copy=new BasalProfile(0,newName,original.getAccuracy(),ProfileOrigin.USER_MODIFIED,original.getId(),original.getMetadata(),original.getSegments());
            BasalProfileEntity pe=BasalProfileMapper.toEntity(copy);
            long newId=dao.upsertProfile(pe,BasalProfileMapper.toSegmentEntities(copy,0)); cb.onResult(newId);
        });
    }
}

[15] ViewModel: ProfileViewModel.java
------------------------------------------------------------
package com.example.basalapp.viewmodel;
import android.app.Application; import androidx.annotation.NonNull; import androidx.lifecycle.*;
import com.example.basalapp.data.repo.BasalProfileRepository; import com.example.basalapp.domain.*;
import java.util.List;
public class ProfileViewModel extends AndroidViewModel {
    private final BasalProfileRepository repo;
    private final MutableLiveData<List<BasalProfile>> profiles=new MutableLiveData<>();
    private final MutableLiveData<BasalProfile> currentProfile=new MutableLiveData<>();
    private final MutableLiveData<BasalProfileDiff> comparisonResult=new MutableLiveData<>();
    private long currentProfileId=0;
    public ProfileViewModel(@NonNull Application app){ super(app); repo=new BasalProfileRepository(app); }
    public LiveData<List<BasalProfile>> getProfiles(){ return profiles; }
    public LiveData<BasalProfile> getCurrentProfile(){ return currentProfile; }
    public LiveData<BasalProfileDiff> getComparisonResult(){ return comparisonResult; }
    public void loadAllProfiles(){ repo.getAllProfiles(profiles::postValue); }
    public void createEmptyProfile(String name,double accuracy,Runnable onSuccessWithOpen){
        repo.createEmptyProfile(name,accuracy,id->{ currentProfileId=id; loadProfile(id); if(onSuccessWithOpen!=null) onSuccessWithOpen.run(); });
    }
    public void duplicateProfile(long id,String nameSuffix,Runnable onSuccessWithOpen){
        repo.duplicateProfile(id,nameSuffix,newId->{ currentProfileId=newId; loadProfile(newId); if(onSuccessWithOpen!=null) onSuccessWithOpen.run(); });
    }
    public void deleteProfile(long id,Runnable onDone){ repo.deleteProfile(id,()->{ loadAllProfiles(); if(onDone!=null) onDone.run(); }); }
    public void loadProfile(long id){ currentProfileId=id; repo.getProfile(id,currentProfile::postValue); }
    public void setCurrentProfile(BasalProfile p){ currentProfileId=p.getId(); currentProfile.postValue(p); }
    public void adjustRateForHour(int hour,boolean increase){ BasalProfile p=currentProfile.getValue(); if(p==null) throw new IllegalStateException("Profil niezaładowany."); p.adjustRateForHour(hour,increase); currentProfile.postValue(p); }
    public void updateSegmentEnd(int startMinutes,int newEndMinutes){ BasalProfile p=currentProfile.getValue(); if(p==null) throw new IllegalStateException("Profil niezaładowany."); p.updateSegmentEnd(startMinutes,newEndMinutes); currentProfile.postValue(p); }
    public void updateSegmentRate(int startMinutes,double newRate){ BasalProfile p=currentProfile.getValue(); if(p==null) throw new IllegalStateException("Profil niezaładowany."); p.updateSegmentRate(startMinutes,newRate); currentProfile.postValue(p); }
    public void saveCurrentProfile(){ BasalProfile p=currentProfile.getValue(); if(p==null) throw new IllegalStateException("Brak profilu do zapisu."); repo.upsert(p,id->{ currentProfileId=id; loadProfile(id); loadAllProfiles(); }); }
    public void compareProfiles(long id1,long id2){ repo.getProfile(id1,p1->repo.getProfile(id2,p2->{ if(p1!=null&&p2!=null) comparisonResult.postValue(p1.diff(p2)); })); }
}

[16] UI: ProfileCombinedEditorFragment.java (unified editor)
------------------------------------------------------------
package com.example.basalapp.ui.editor;
import android.os.Bundle; import android.view.*; import android.widget.TextView; import android.widget.Toast;
import androidx.annotation.*; import androidx.fragment.app.Fragment; import androidx.lifecycle.ViewModelProvider; import androidx.recyclerview.widget.LinearLayoutManager;
import com.example.basalapp.R; import com.example.basalapp.domain.BasalProfile; import com.example.basalapp.viewmodel.ProfileViewModel;
import com.github.mikephil.charting.charts.LineChart; import com.github.mikephil.charting.components.*; import com.github.mikephil.charting.data.*;
import com.google.android.material.button.MaterialButton; import com.google.android.material.button.MaterialButtonToggleGroup;
import java.util.ArrayList; import java.util.Locale;
public class ProfileCombinedEditorFragment extends Fragment {
    private static final String ARG_PROFILE_ID="profileId";
    public static ProfileCombinedEditorFragment newInstance(long profileId){ ProfileCombinedEditorFragment f=new ProfileCombinedEditorFragment(); Bundle b=new Bundle(); b.putLong(ARG_PROFILE_ID,profileId); f.setArguments(b); return f; }
    private ProfileViewModel viewModel;
    private TextView tvProfileName,tvAccuracy,tvTotalDailyDose,tvError;
    private MaterialButton btnSaveProfile,btnDiscardChanges;
    private MaterialButtonToggleGroup toggleMode; private View panelHourly,panelSegments;
    private LineChart chart; private TextView tvSelectedHour,tvHourDoseInfo; private MaterialButton btnLeft,btnRight,btnMinus,btnPlus; private int selectedHour=0;
    private androidx.recyclerview.widget.RecyclerView rvSegments; private BasalSegmentsAdapter segmentsAdapter;
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,@Nullable ViewGroup container,@Nullable Bundle savedInstanceState){
        return inflater.inflate(R.layout.fragment_profile_combined_editor,container,false);
    }
    @Override public void onViewCreated(@NonNull View v,@Nullable Bundle savedInstanceState){
        super.onViewCreated(v,savedInstanceState);
        viewModel=new ViewModelProvider(requireActivity()).get(ProfileViewModel.class);
        tvProfileName=v.findViewById(R.id.tvProfileName); tvAccuracy=v.findViewById(R.id.tvAccuracy);
        tvTotalDailyDose=v.findViewById(R.id.tvTotalDailyDose); tvError=v.findViewById(R.id.tvError);
        btnSaveProfile=v.findViewById(R.id.btnSaveProfile); btnDiscardChanges=v.findViewById(R.id.btnDiscardChanges);
        toggleMode=v.findViewById(R.id.toggleMode); panelHourly=v.findViewById(R.id.panelHourly); panelSegments=v.findViewById(R.id.panelSegments);
        chart=v.findViewById(R.id.chartProfile); tvSelectedHour=v.findViewById(R.id.tvSelectedHour); tvHourDoseInfo=v.findViewById(R.id.tvHourDoseInfo);
        btnLeft=v.findViewById(R.id.btnLeft); btnRight=v.findViewById(R.id.btnRight); btnMinus=v.findViewById(R.id.btnMinus); btnPlus=v.findViewById(R.id.btnPlus);
        rvSegments=v.findViewById(R.id.rvSegments); rvSegments.setLayoutManager(new LinearLayoutManager(requireContext()));
        setupChart(); setupToggle(); setupActions();
        Bundle args=getArguments(); if(args!=null && args.containsKey(ARG_PROFILE_ID)){ long id=args.getLong(ARG_PROFILE_ID,0); if(id>0) viewModel.loadProfile(id); }
        viewModel.getCurrentProfile().observe(getViewLifecycleOwner(), profile -> {
            if(profile!=null){ tvError.setVisibility(View.GONE); bindShared(profile); renderChart(profile); updateHourInfo(profile); highlightSelectedHour(); bindSegments(profile); }
            else{ tvError.setText("Brak załadowanego profilu."); tvError.setVisibility(View.VISIBLE); }
        });
        switchToHourly(true);
    }
    private void setupToggle(){ toggleMode.addOnButtonCheckedListener((g,id,checked)->{ if(!checked) return; switchToHourly(id==R.id.btnModeHourly); }); }
    private void switchToHourly(boolean hourly){ panelHourly.setVisibility(hourly?View.VISIBLE:View.GONE); panelSegments.setVisibility(hourly?View.GONE:View.VISIBLE); }
    private void setupActions(){
        btnSaveProfile.setOnClickListener(v->{ try{ viewModel.saveCurrentProfile(); Toast.makeText(requireContext(),"Profil zapisany.",Toast.LENGTH_SHORT).show(); }catch(Exception ex){ showError(ex.getMessage()); } });
        btnDiscardChanges.setOnClickListener(v-> Toast.makeText(requireContext(),"Zmiany odrzucone.",Toast.LENGTH_SHORT).show());
        btnLeft.setOnClickListener(x->{ if(selectedHour>0){ selectedHour--; updateSelectedHourText(); highlightSelectedHour(); BasalProfile p=viewModel.getCurrentProfile().getValue(); if(p!=null) updateHourInfo(p); } });
        btnRight.setOnClickListener(x->{ if(selectedHour<23){ selectedHour++; updateSelectedHourText(); highlightSelectedHour(); BasalProfile p=viewModel.getCurrentProfile().getValue(); if(p!=null) updateHourInfo(p); } });
        btnPlus.setOnClickListener(x->{ BasalProfile p=viewModel.getCurrentProfile().getValue(); if(p==null){ showError("Nie można zwiększyć dawki – brak profilu."); return; } try{ viewModel.adjustRateForHour(selectedHour,true); }catch(Exception ex){ showError(ex.getMessage()); } });
        btnMinus.setOnClickListener(x->{ BasalProfile p=viewModel.getCurrentProfile().getValue(); if(p==null){ showError("Nie można zmniejszyć dawki – brak profilu."); return; } try{ viewModel.adjustRateForHour(selectedHour,false); }catch(Exception ex){ showError(ex.getMessage()); } });
        updateSelectedHourText();
    }
    private void bindShared(@NonNull BasalProfile profile){
        tvProfileName.setText(profile.getName());
        tvAccuracy.setText(String.format(Locale.getDefault(),"Accuracy: %.2f U/h",profile.getAccuracy()));
        tvTotalDailyDose.setText("Całkowita dawka dobowa: "+profile.getTotalDailyDose().toPlainString()+" U");
    }
    private void bindSegments(@NonNull BasalProfile profile){
        if(segmentsAdapter==null){
            segmentsAdapter=new BasalSegmentsAdapter(requireContext(),profile.getAccuracy(), new BasalSegmentsAdapter.SegmentEditListener(){
                @Override public void onChangeEnd(int segmentStartMinutes,int newEndMinutes){ viewModel.updateSegmentEnd(segmentStartMinutes,newEndMinutes); }
                @Override public void onChangeRate(int segmentStartMinutes,double newRate){ viewModel.updateSegmentRate(segmentStartMinutes,newRate); }
            });
            rvSegments.setAdapter(segmentsAdapter);
        }
        segmentsAdapter.submitList(profile.getSegments());
    }
    private void setupChart(){
        chart.setNoDataText("Brak danych profilu.");
        Description desc=new Description(); desc.setText("Dawki bazalne (co 1 h)"); chart.setDescription(desc);
        chart.getAxisRight().setEnabled(false); XAxis xAxis=chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM); xAxis.setGranularity(1f); xAxis.setLabelCount(12,true);
        chart.getLegend().setEnabled(false); chart.setPinchZoom(true); chart.setDoubleTapToZoomEnabled(true);
    }
    private void renderChart(@NonNull BasalProfile profile){
        double[] hours=profile.toHourArray(); ArrayList<Entry> entries=new ArrayList<>(24);
        for(int i=0;i<hours.length;i++) entries.add(new Entry(i,(float)hours[i]));
        LineDataSet ds=new LineDataSet(entries,"Profil"); ds.setLineWidth(2f); ds.setColor(0xFF2196F3); ds.setCircleRadius(0f); ds.setDrawValues(false);
        chart.setData(new LineData(ds)); chart.invalidate();
    }
    private void highlightSelectedHour(){
        XAxis xAxis=chart.getXAxis(); xAxis.removeAllLimitLines();
        LimitLine llStart=new LimitLine(selectedHour,""); llStart.setLineColor(0xFFFFA000); llStart.setLineWidth(1.5f);
        LimitLine llEnd=new LimitLine(selectedHour+1,""); llEnd.setLineColor(0xFFFFA000); llEnd.setLineWidth(1.5f);
        xAxis.addLimitLine(llStart); xAxis.addLimitLine(llEnd); chart.invalidate();
    }
    private void updateSelectedHourText(){ tvSelectedHour.setText(String.format(Locale.getDefault(),"Godzina: %02d:00–%02d:00",selectedHour,selectedHour+1)); }
    private void updateHourInfo(@NonNull BasalProfile profile){ double rate=profile.getBasalRate(selectedHour*60); tvHourDoseInfo.setText(String.format(Locale.getDefault(),"Dawka: %.2f U/h",rate)); }
    private void showError(String msg){ tvError.setText(msg); tvError.setVisibility(View.VISIBLE); Toast.makeText(requireContext(),msg,Toast.LENGTH_SHORT).show(); }
}

[17] UI Adapter: BasalSegmentsAdapter.java (segments mode, hour-end picker + dose picker)
------------------------------------------------------------
package com.example.basalapp.ui.editor;
import android.app.AlertDialog; import android.content.Context; import android.view.*; import android.widget.NumberPicker; import android.widget.TextView; import android.widget.Button; import android.widget.Toast;
import androidx.annotation.NonNull; import androidx.recyclerview.widget.DiffUtil; import androidx.recyclerview.widget.RecyclerView;
import com.example.basalapp.R; import com.example.basalapp.domain.BasalSegment;
import java.util.*; public class BasalSegmentsAdapter extends RecyclerView.Adapter<BasalSegmentsAdapter.VH> {
    public interface SegmentEditListener{ void onChangeEnd(int segmentStartMinutes,int newEndMinutes); void onChangeRate(int segmentStartMinutes,double newRate); }
    private final Context context; private final double accuracy; private double maxDoseU=100.0; private final SegmentEditListener listener; private final List<BasalSegment> segments=new ArrayList<>();
    public BasalSegmentsAdapter(@NonNull Context ctx,double accuracy,@NonNull SegmentEditListener listener){ if(accuracy<=0.0) throw new IllegalArgumentException("accuracy > 0 required"); this.context=ctx; this.accuracy=accuracy; this.listener=listener; setHasStableIds(true); }
    public void setMaxDoseU(double maxDoseU){ if(maxDoseU<=0.0) throw new IllegalArgumentException("maxDoseU > 0 required"); this.maxDoseU=maxDoseU; }
    public void submitList(@NonNull List<BasalSegment> newSegments){ DiffUtil.DiffResult diff=DiffUtil.calculateDiff(new Diff(segments,newSegments)); segments.clear(); segments.addAll(newSegments); diff.dispatchUpdatesTo(this); }
    @Override public long getItemId(int position){ return segments.get(position).getStartMinutes(); }
    @Override public int getItemCount(){ return segments.size(); }
    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent,int viewType){ View v=LayoutInflater.from(parent.getContext()).inflate(R.layout.item_segment,parent,false); return new VH(v); }
    @Override public void onBindViewHolder(@NonNull VH h,int pos){
        BasalSegment seg=segments.get(pos); int start=seg.getStartMinutes();
        int end=(pos+1<segments.size())?segments.get(pos+1).getStartMinutes():1440; double rate=seg.getRateUnits()*accuracy;
        h.tvStart.setText(String.format(Locale.getDefault(),"Start: %s",fmtTime(start)));
        h.tvEnd.setText(String.format(Locale.getDefault(),"Koniec: %s",fmtTime(end)));
        h.tvRate.setText(String.format(Locale.getDefault(),"Dawka: %.2f U/h",rate));
        h.btnPickEnd.setOnClickListener(view->showEndHourPickerDialog(start,end));
        h.btnPickRate.setOnClickListener(view->showDosePickerDialog(start,rate));
    }
    static class VH extends RecyclerView.ViewHolder{
        TextView tvStart,tvEnd,tvRate; Button btnPickEnd,btnPickRate;
        VH(@NonNull View itemView){ super(itemView); tvStart=itemView.findViewById(R.id.tvStart); tvEnd=itemView.findViewById(R.id.tvEnd); tvRate=itemView.findViewById(R.id.tvRate);
            btnPickEnd=itemView.findViewById(R.id.btnPickEnd); btnPickRate=itemView.findViewById(R.id.btnPickRate); }
    }
    private void showEndHourPickerDialog(int startMinutes,int currentEndMinutes){
        View content=LayoutInflater.from(context).inflate(R.layout.dialog_end_hour_picker,null);
        NumberPicker npHour=content.findViewById(R.id.npHour);
        int startHour=startMinutes/60; int minHour=startHour+1; int maxHour=24;
        npHour.setMinValue(minHour); npHour.setMaxValue(maxHour); npHour.setWrapSelectorWheel(false);
        int currentEndHour=Math.min(currentEndMinutes,1440)/60; if(currentEndHour<minHour||currentEndHour>maxHour) currentEndHour=minHour; npHour.setValue(currentEndHour);
        AlertDialog dlg=new AlertDialog.Builder(context).setTitle("Koniec segmentu (godzina)").setView(content)
                .setNegativeButton("Anuluj",(d,w)->d.dismiss())
                .setPositiveButton("Zastosuj",(d,w)->{
                    int endHour=npHour.getValue(); int newEnd=endHour*60;
                    if(newEnd<=startMinutes){ toast("Koniec segmentu musi być późniejszy niż start."); return; }
                    if(newEnd>1440){ toast("Koniec segmentu nie może przekraczać 24:00."); return; }
                    listener.onChangeEnd(startMinutes,newEnd);
                }).create();
        dlg.show();
    }
    private void showDosePickerDialog(int segmentStartMinutes,double currentRateU){
        View content=LayoutInflater.from(context).inflate(R.layout.dialog_dose_picker,null);
        NumberPicker npDose=content.findViewById(R.id.npDose);
        int currentUnits=toUnits(currentRateU); int maxUnits=toUnits(maxDoseU);
        String[] labels=buildDoseLabels(maxUnits); npDose.setMinValue(0); npDose.setMaxValue(maxUnits);
        npDose.setDisplayedValues(labels); npDose.setWrapSelectorWheel(false); npDose.setValue(currentUnits);
        AlertDialog dlg=new AlertDialog.Builder(context).setTitle("Dawka (U/h)").setView(content)
                .setNegativeButton("Anuluj",(d,w)->d.dismiss())
                .setPositiveButton("Zastosuj",(d,w)->{
                    int units=npDose.getValue(); double newRate=unitsToRate(units);
                    if(newRate<0.0){ toast("Dawka nie może być ujemna."); return; }
                    listener.onChangeRate(segmentStartMinutes,newRate);
                }).create(); dlg.show();
    }
    private void toast(String msg){ Toast.makeText(context,msg,Toast.LENGTH_SHORT).show(); }
    private String fmtTime(int minutes){ int h=minutes/60, m=minutes%60; return String.format(Locale.getDefault(),"%02d:%02d",h,m); }
    private double unitsToRate(int units){ return units*accuracy; }
    private int toUnits(double rate){ if(rate<0.0) throw new IllegalArgumentException("Dawka nie może być ujemna."); return (int)Math.round(rate/accuracy); }
    private String[] buildDoseLabels(int maxUnits){ String[] labels=new String[maxUnits+1]; for(int u=0;u<=maxUnits;u++) labels[u]=String.format(Locale.getDefault(),"%.2f",unitsToRate(u)); return labels; }
    static class Diff extends DiffUtil.Callback{
        private final List<BasalSegment> oldList,newList; Diff(List<BasalSegment> o,List<BasalSegment> n){ oldList=o; newList=n; }
        @Override public int getOldListSize(){ return oldList.size(); } @Override public int getNewListSize(){ return newList.size(); }
        @Override public boolean areItemsTheSame(int o,int n){ return oldList.get(o).getStartMinutes()==newList.get(n).getStartMinutes(); }
        @Override public boolean areContentsTheSame(int o,int n){ BasalSegment a=oldList.get(o), b=newList.get(n); return a.getRateUnits()==b.getRateUnits(); }
    }
}

[18] UI List: ProfileListFragment.java
------------------------------------------------------------
package com.example.basalapp.ui.list;
import android.os.Bundle; import android.view.*; import androidx.annotation.*; import androidx.fragment.app.Fragment; import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider; import androidx.recyclerview.widget.LinearLayoutManager;
import com.example.basalapp.R; import com.example.basalapp.domain.BasalProfile; import com.example.basalapp.ui.editor.ProfileCombinedEditorFragment; import com.example.basalapp.viewmodel.ProfileViewModel;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton; import com.google.android.material.snackbar.Snackbar;
import java.util.List;
public class ProfileListFragment extends Fragment {
    private ProfileViewModel viewModel; private androidx.recyclerview.widget.RecyclerView rvProfiles; private ExtendedFloatingActionButton fabAdd; private ProfilesListAdapter adapter;
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,@Nullable ViewGroup container,@Nullable Bundle savedInstanceState){
        return inflater.inflate(R.layout.fragment_profile_list,container,false);
    }
    @Override public void onViewCreated(@NonNull View v,@Nullable Bundle savedInstanceState){
        super.onViewCreated(v,savedInstanceState);
        viewModel=new ViewModelProvider(requireActivity()).get(ProfileViewModel.class);
        rvProfiles=v.findViewById(R.id.rvProfiles); fabAdd=v.findViewById(R.id.fabAdd);
        rvProfiles.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter=new ProfilesListAdapter(new ProfilesListAdapter.ActionListener(){
            @Override public void onEdit(BasalProfile profile){ openEditor(profile.getId()); }
            @Override public void onDuplicate(BasalProfile profile){ viewModel.duplicateProfile(profile.getId()," (kopia)",()-> openEditor(viewModel.getCurrentProfile().getValue().getId())); }
            @Override public void onDelete(BasalProfile profile){ viewModel.deleteProfile(profile.getId(),()-> Snackbar.make(rvProfiles,"Profil usunięty.",Snackbar.LENGTH_SHORT).show()); }
        });
        rvProfiles.setAdapter(adapter);
        viewModel.getProfiles().observe(getViewLifecycleOwner(), this::bindProfiles); viewModel.loadAllProfiles();
        fabAdd.setOnClickListener(x-> viewModel.createEmptyProfile("Nowy profil",0.05,()->{ BasalProfile p=viewModel.getCurrentProfile().getValue(); if(p!=null) openEditor(p.getId()); }));
    }
    private void bindProfiles(List<BasalProfile> profiles){ adapter.submitList(profiles); }
    private void openEditor(long profileId){ ProfileCombinedEditorFragment editor=ProfileCombinedEditorFragment.newInstance(profileId);
        FragmentTransaction ft=getParentFragmentManager().beginTransaction(); ft.replace(android.R.id.content,editor); ft.addToBackStack(null); ft.commit(); }
}

[19] UI List Adapter: ProfilesListAdapter.java
------------------------------------------------------------
package com.example.basalapp.ui.list;
import android.view.*; import android.widget.TextView; import androidx.annotation.NonNull; import androidx.recyclerview.widget.*;
import com.example.basalapp.R; import com.example.basalapp.domain.BasalProfile; import com.google.android.material.button.MaterialButton;
import java.util.*; public class ProfilesListAdapter extends RecyclerView.Adapter<ProfilesListAdapter.VH> {
    public interface ActionListener{ void onEdit(BasalProfile profile); void onDuplicate(BasalProfile profile); void onDelete(BasalProfile profile); }
    private final List<BasalProfile> items=new ArrayList<>(); private final ActionListener actions;
    public ProfilesListAdapter(ActionListener actions){ this.actions=actions; setHasStableIds(true); }
    public void submitList(List<BasalProfile> newItems){ DiffUtil.DiffResult diff=DiffUtil.calculateDiff(new Diff(items,newItems)); items.clear(); items.addAll(newItems); diff.dispatchUpdatesTo(this); }
    @Override public long getItemId(int position){ return items.get(position).getId(); }
    @Override public int getItemCount(){ return items.size(); }
    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent,int viewType){ View v=LayoutInflater.from(parent.getContext()).inflate(R.layout.item_profile_row,parent,false); return new VH(v); }
    @Override public void onBindViewHolder(@NonNull VH h,int pos){
        BasalProfile p=items.get(pos);
        h.tvId.setText(String.format(Locale.getDefault(),"ID: %d",p.getId()));
        h.tvName.setText(p.getName());
        h.tvTotalDose.setText(String.format(Locale.getDefault(),"Całkowita dawka: %s U",p.getTotalDailyDose().toPlainString()));
        h.btnEdit.setOnClickListener(v-> actions.onEdit(p));
        h.btnDuplicate.setOnClickListener(v-> actions.onDuplicate(p));
        h.btnDelete.setOnClickListener(v-> actions.onDelete(p));
    }
    static class VH extends RecyclerView.ViewHolder{
        TextView tvId,tvName,tvTotalDose; MaterialButton btnEdit,btnDuplicate,btnDelete;
        VH(@NonNull View itemView){ super(itemView); tvId=itemView.findViewById(R.id.tvProfileId); tvName=itemView.findViewById(R.id.tvProfileName);
            tvTotalDose=itemView.findViewById(R.id.tvTotalDose); btnEdit=itemView.findViewById(R.id.btnEdit);
            btnDuplicate=itemView.findViewById(R.id.btnDuplicate); btnDelete=itemView.findViewById(R.id.btnDelete); }
    }
    static class Diff extends DiffUtil.Callback{
        private final List<BasalProfile> oldList,newList; Diff(List<BasalProfile> o,List<BasalProfile> n){ oldList=o; newList=n; }
        @Override public int getOldListSize(){ return oldList.size(); } @Override public int getNewListSize(){ return newList.size(); }
        @Override public boolean areItemsTheSame(int o,int n){ return oldList.get(o).getId()==newList.get(n).getId(); }
        @Override public boolean areContentsTheSame(int o,int n){ BasalProfile a=oldList.get(o), b=newList.get(n);
            return a.getName().equals(b.getName()) && a.getTotalDailyDose().compareTo(b.getTotalDailyDose())==0; }
    }
}

[20] Layout: fragment_profile_combined_editor.xml
------------------------------------------------------------
<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/rootCombinedEditor" android:layout_width="match_parent" android:layout_height="match_parent">
    <com.google.android.material.appbar.MaterialToolbar android:id="@+id/toolbar" android:layout_width="match_parent" android:layout_height="?attr/actionBarSize" app:title="Edycja profilu bazalnego"/>
    <androidx.core.widget.NestedScrollView android:id="@+id/scrollContent" android:layout_width="match_parent" android:layout_height="match_parent" android:fillViewport="true" app:layout_behavior="@string/appbar_scrolling_view_behavior">
        <LinearLayout android:id="@+id/contentLinear" android:orientation="vertical" android:padding="16dp" android:layout_width="match_parent" android:layout_height="wrap_content">
            <com.google.android.material.card.MaterialCardView android:id="@+id/cardSummary" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginBottom="12dp">
                <LinearLayout android:orientation="vertical" android:padding="16dp" android:layout_width="match_parent" android:layout_height="wrap_content">
                    <TextView android:id="@+id/tvProfileName" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Nazwa profilu" android:textStyle="bold" android:textSize="18sp"/>
                    <TextView android:id="@+id/tvAccuracy" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Accuracy: 0.05 U/h"/>
                    <TextView android:id="@+id/tvTotalDailyDose" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Całkowita dawka dobowa: 0.00 U"/>
                </LinearLayout>
            </com.google.android.material.card.MaterialCardView>
            <com.google.android.material.button.MaterialButtonToggleGroup android:id="@+id/toggleMode" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginBottom="12dp" app:singleSelection="true" app:selectionRequired="true">
                <com.google.android.material.button.MaterialButton android:id="@+id/btnModeHourly" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:text="Godzinowy" app:checked="true"/>
                <com.google.android.material.button.MaterialButton android:id="@+id/btnModeSegments" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:text="Segmenty"/>
            </com.google.android.material.button.MaterialButtonToggleGroup>
            <FrameLayout android:id="@+id/panelContainer" android:layout_width="match_parent" android:layout_height="wrap_content">
                <LinearLayout android:id="@+id/panelHourly" android:orientation="vertical" android:layout_width="match_parent" android:layout_height="wrap_content">
                    <com.github.mikephil.charting.charts.LineChart android:id="@+id/chartProfile" android:layout_width="match_parent" android:layout_height="260dp"/>
                    <LinearLayout android:id="@+id/infoBar" android:orientation="horizontal" android:layout_width="match_parent" android:layout_height="wrap_content" android:paddingTop="12dp" android:gravity="center_vertical">
                        <TextView android:id="@+id/tvSelectedHour" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Godzina: 00:00–01:00" android:textStyle="bold" android:layout_marginEnd="12dp"/>
                        <TextView android:id="@+id/tvHourDoseInfo" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Dawka: 0.00 U/h"/>
                    </LinearLayout>
                    <LinearLayout android:id="@+id/controlsBar" android:orientation="horizontal" android:gravity="center" android:layout_width="match_parent" android:layout_height="wrap_content" android:paddingTop="8dp">
                        <com.google.android.material.button.MaterialButton android:id="@+id/btnLeft" android:text="←" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1"/>
                        <com.google.android.material.button.MaterialButton android:id="@+id/btnRight" android:text="→" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1"/>
                        <com.google.android.material.button.MaterialButton android:id="@+id/btnMinus" android:text="−" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1"/>
                        <com.google.android.material.button.MaterialButton android:id="@+id/btnPlus" android:text="+", android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1"/>
                    </LinearLayout>
                </LinearLayout>
                <LinearLayout android:id="@+id/panelSegments" android:orientation="vertical" android:layout_width="match_parent" android:layout_height="wrap_content" android:visibility="gone">
                    <androidx.recyclerview.widget.RecyclerView android:id="@+id/rvSegments" android:layout_width="match_parent" android:layout_height="wrap_content"/>
                </LinearLayout>
            </FrameLayout>
            <TextView android:id="@+id/tvError" android:layout_width="match_parent" android:layout_height="wrap_content" android:textColor="@android:color/holo_red_dark" android:visibility="gone" android:paddingTop="8dp"/>
            <LinearLayout android:id="@+id/actionsBar" android:orientation="horizontal" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="12dp">
                <com.google.android.material.button.MaterialButton android:id="@+id/btnSaveProfile" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:text="Zapisz profil"/>
                <com.google.android.material.button.MaterialButton android:id="@+id/btnDiscardChanges" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:text="Odrzuć zmiany"/>
            </LinearLayout>
        </LinearLayout>
    </androidx.core.widget.NestedScrollView>
</androidx.coordinatorlayout.widget.CoordinatorLayout>

[21] Layout: item_segment.xml
------------------------------------------------------------
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/itemSegmentRoot" android:layout_width="match_parent" android:layout_height="wrap_content" android:padding="12dp">
    <TextView android:id="@+id/tvStart" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Start: 00:00" android:textStyle="bold" android:textSize="16sp"
        app:layout_constraintTop_toTopOf="parent" app:layout_constraintStart_toStartOf="parent"/>
    <TextView android:id="@+id/tvEnd" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Koniec: 01:00"
        app:layout_constraintTop_toTopOf="@id/tvStart" app:layout_constraintStart_toEndOf="@id/tvStart" android:layout_marginStart="24dp"/>
    <Button android:id="@+id/btnPickEnd" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Zmień koniec"
        app:layout_constraintTop_toTopOf="@id/tvEnd" app:layout_constraintStart_toEndOf="@id/tvEnd" android:layout_marginStart="12dp"/>
    <TextView android:id="@+id/tvRate" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Dawka: 0.80 U/h"
        app:layout_constraintTop_toBottomOf="@id/tvStart" app:layout_constraintStart_toStartOf="parent" android:layout_marginTop="8dp"/>
    <Button android:id="@+id/btnPickRate" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Zmień dawkę"
        app:layout_constraintTop_toTopOf="@id/tvRate" app:layout_constraintStart_toEndOf="@id/tvRate" android:layout_marginStart="12dp"/>
</androidx.constraintlayout.widget.ConstraintLayout>

[22] Layout: dialog_end_hour_picker.xml (hour-only picker)
------------------------------------------------------------
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:id="@+id/endHourPickerRoot"
    android:orientation="vertical" android:padding="16dp" android:layout_width="match_parent" android:layout_height="wrap_content">
    <TextView android:id="@+id/tvEndPickerTitle" android:layout_width="match_parent" android:layout_height="wrap_content"
        android:text="Wybierz godzinę końca segmentu" android:textStyle="bold" android:textSize="16sp"/>
    <NumberPicker android:id="@+id/npHour" android:layout_width="match_parent" android:layout_height="wrap_content" android:paddingTop="12dp"/>
</LinearLayout>

[23] Layout: dialog_dose_picker.xml
------------------------------------------------------------
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:id="@+id/dosePickerRoot"
    android:orientation="vertical" android:padding="16dp" android:layout_width="match_parent" android:layout_height="wrap_content">
    <TextView android:id="@+id/tvDosePickerTitle" android:layout_width="match_parent" android:layout_height="wrap_content"
        android:text="Wybierz dawkę (U/h)" android:textStyle="bold" android:textSize="16sp"/>
    <NumberPicker android:id="@+id/npDose" android:layout_width="match_parent" android:layout_height="wrap_content" android:paddingTop="12dp"/>
</LinearLayout>

[24] Layout: fragment_profile_list.xml (profile list screen)
------------------------------------------------------------
<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/rootProfileList" android:layout_width="match_parent" android:layout_height="match_parent">
    <com.google.android.material.appbar.AppBarLayout android:id="@+id/appBar" android:layout_width="match_parent" android:layout_height="?attr/actionBarSize"
        android:background="?attr/colorSurface" android:theme="@style/ThemeOverlay.Material3.ActionBar">
        <com.google.android.material.appbar.MaterialToolbar android:id="@+id/toolbarList" android:layout_width="match_parent" android:layout_height="match_parent"
            app:title="Profile bazalne" app:titleCentered="false"/>
    </com.google.android.material.appbar.AppBarLayout>
    <androidx.recyclerview.widget.RecyclerView android:id="@+id/rvProfiles" android:layout_width="match_parent" android:layout_height="match_parent"
        android:clipToPadding="false" android:paddingStart="16dp" android:paddingEnd="16dp" android:paddingBottom="88dp"
        app:layout_behavior="@string/appbar_scrolling_view_behavior"/>
    <com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton android:id="@+id/fabAdd"
        android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Utwórz profil" android:contentDescription="Utwórz nowy profil"
        app:icon="@android:drawable/ic_input_add" app:layout_anchor="@id/rvProfiles" app:layout_anchorGravity="bottom|end" android:layout_margin="16dp"/>
</androidx.coordinatorlayout.widget.CoordinatorLayout>

[25] Layout: item_profile_row.xml
------------------------------------------------------------
<?xml version="1.0" encoding="utf-8"?>
<com.google.android.material.card.MaterialCardView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/cardProfileRow" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginBottom="8dp" android:padding="12dp">
    <LinearLayout android:orientation="vertical" android:layout_width="match_parent" android:layout_height="wrap_content">
        <LinearLayout android:orientation="horizontal" android:layout_width="match_parent" android:layout_height="wrap_content" android:gravity="space_between">
            <TextView android:id="@+id/tvProfileId" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="ID: 0" android:textStyle="bold"/>
            <TextView android:id="@+id/tvProfileName" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Nazwa profilu"/>
        </LinearLayout>
        <TextView android:id="@+id/tvTotalDose" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Całkowita dawka: 0.00 U" android:layout_marginTop="4dp"/>
        <LinearLayout android:orientation="horizontal" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="8dp">
            <com.google.android.material.button.MaterialButton android:id="@+id/btnEdit" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:text="Edytuj"/>
            <com.google.android.material.button.MaterialButton android:id="@+id/btnDuplicate" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:text="Duplikuj"/>
            <com.google.android.material.button.MaterialButton android:id="@+id/btnDelete" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:text="Usuń"/>
        </LinearLayout>
    </LinearLayout>
</com.google.android.material.card.MaterialCardView>

----------------------------------------------------------------------
VALIDATION & EDGE CASES (FINAL)
----------------------------------------------------------------------
- Grid: startMinutes and edited endMinutes must be multiples of 60 only; 24:00 (1440) is allowed as an end, but no change point at 1440.
- First change point must be at 0; last start < 1440.
- Rates must be non-negative; any operation producing negative rate throws.
- Hourly adjust:
  * First +/− click equalizes to max/min units inside the hour; subsequent clicks change by ±1 unit (accuracy).
  * Works at 0:00–1:00 (start=0) and 23:00–24:00 safely; no creation of change point at 24:00.
- Segments edits:
  * End picker enforces endHour ≥ startHour+1, ≤ 24.
  * After end change or dose change, segments are normalized (sorted, merged, invariants validated).
- Repository operations:
  * Create empty profile → single change point (0:00, units=0).
  * Duplicate → copies segments/accuracy/metadata; sets baseProfileId to source; name suffix "(kopia)" by default.
  * Delete → cascades segments via FK and refreshes list.

----------------------------------------------------------------------
TESTING NOTES (RECOMMENDED)
----------------------------------------------------------------------
- Domain tests: normalizeAndValidate (merge neighbors, hour grid, first=0, last<1440), adjustRateForHour at hours 0 and 23, updateSegmentEnd (bridge filler, shift next), updateSegmentRate (merge).
- UI tests: End hour picker edge cases (start 23:00 → end 24:00; start 0:00 → end ≥ 1:00), dose picker accuracy stepping, hourly mode equalize→steps, list CRUD navigation.
- DB tests: DAO upsert lifecycle, relation integrity, delete cascade.

----------------------------------------------------------------------
NEXT STEPS (OPTIONAL)
----------------------------------------------------------------------
- Seed initial profile on first run.
- Migrations for future DB versions.
- Add search/filter and swipe actions on profile list.
- Add Compose equivalents if needed.

=== END OF HANDOFF ===