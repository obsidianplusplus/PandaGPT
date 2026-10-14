package cn.panda.pandagpt.perception;

import cn.panda.pandagpt.vision.*;
import java.util.*;

/** 一次已完成的观察：表面区域、局部空间报告、实体与方块状态快照。不可变，可跨线程传递。 */
public record Observation(
        UUID captureId,
        UUID world,
        long time,
        Camera camera,
        int range,
        SceneObjects.Analysis surfaces,
        SpatialSurvey.Report spatial,
        List<EntityObservation> entities,
        Map<SceneObjects.Pos, String> observedBlocks) {
    public Observation {
        entities = List.copyOf(entities);
        observedBlocks = Map.copyOf(observedBlocks);
    }

    public static Observation create(
            UUID id,
            UUID world,
            long time,
            Camera camera,
            int range,
            List<RaySample> pixels,
            SpatialSurvey.Report spatial,
            List<EntityObservation> entities) {
        Map<SceneObjects.Pos, String> blocks = new LinkedHashMap<>();
        for (RaySample p : pixels)
            if (p.kind() == RaySample.Kind.HIT && blocks.size() < 2048)
                blocks.put(SceneObjects.Pos.of(p), p.blockData());
        return new Observation(
                id,
                world,
                time,
                camera,
                range,
                SceneObjects.analyze(camera, pixels),
                spatial,
                EntityObservation.project(entities, camera, range, pixels),
                blocks);
    }

    public String context() {
        StringBuilder out =
                new StringBuilder("capture=" + captureId + ", world_uuid=" + world + ", sampled_at_ms=" + time + "\n");
        out.append(surfaces.context()).append('\n').append(spatial.context()).append('\n');
        out.append(
                "Entity observations (bounded sample, may be omitted under server load; an empty list never proves absence; bounding-box rays, no actual entity textures):\n");
        int number = 0;
        for (EntityObservation e : entities)
            out.append("E")
                    .append(++number)
                    .append(" ")
                    .append(e.type())
                    .append(" uuid=")
                    .append(e.uuid())
                    .append(" item=")
                    .append(e.item())
                    .append(" visibility=")
                    .append(e.visibility())
                    .append(" targeting_player=")
                    .append(e.targetingPlayer())
                    .append(" distance=")
                    .append(Math.round(e.center().distance(camera.origin()) * 10) / 10.0)
                    .append('\n');
        return out.toString();
    }
}
