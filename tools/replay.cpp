// Host-side replay of a raw .livox recording through the LIO core.
// usage: replay <file.livox> [out_prefix]
//   writes <prefix>_traj.csv (t,x,y,z) and <prefix>_map.pcd
#include <cstdio>
#include <fstream>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>

#include "lio.hpp"

int main(int argc, char** argv) {
    if (argc < 2) { std::fprintf(stderr, "usage: %s file.livox [out_prefix]\n", argv[0]); return 1; }
    std::string prefix = argc > 2 ? argv[2] : "out";
    std::ifstream in(argv[1], std::ios::binary);
    if (!in) { std::perror("open"); return 1; }

    mndt::LioConfig cfg;
    std::string loc_map;
    double map_yaw = 0, map_x = 0, map_y = 0;
    double gx = 0, gy = 0, gyaw = 0;
    bool has_gyaw = false;
    for (int i = 3; i < argc; ++i) {
        std::string a = argv[i];
        auto eq = a.find('=');
        if (eq == std::string::npos) continue;
        std::string k = a.substr(0, eq);
        if (k == "loc") { loc_map = a.substr(eq + 1); continue; }
        double v = std::stod(a.substr(eq + 1));
        if (k == "ds") cfg.scan_ds = v;
        else if (k == "map") cfg.map_res = v;
        else if (k == "lc") cfg.laser_cov = v;
        else if (k == "acc") cfg.acc_cov = v;
        else if (k == "gyr") cfg.gyr_cov = v;
        else if (k == "it") cfg.max_iter = int(v);
        else if (k == "pl") cfg.plane_thresh = v;
        else if (k == "blind") cfg.blind = v;
        else if (k == "mapyaw") map_yaw = v * M_PI / 180;
        else if (k == "mapx") map_x = v;
        else if (k == "mapy") map_y = v;
        else if (k == "ndtres") cfg.ndt_res = v;
        else if (k == "gx") gx = v;
        else if (k == "gy") gy = v;
        else if (k == "gyaw") { gyaw = v * M_PI / 180; has_gyaw = true; }
        else std::fprintf(stderr, "unknown key %s\n", k.c_str());
    }
    mndt::Lio lio(cfg);
    if (!loc_map.empty()) {
        // minimal loader for our own binary xyz PCD
        std::ifstream pf(loc_map, std::ios::binary);
        std::string line;
        size_t n = 0;
        while (std::getline(pf, line)) {
            if (line.rfind("POINTS", 0) == 0) n = std::stoul(line.substr(7));
            if (line.rfind("DATA", 0) == 0) break;
        }
        std::vector<Eigen::Vector3f> pts(n);
        pf.read(reinterpret_cast<char*>(pts.data()), n * 12);
        // optionally move the map to test the initial search: p' = Rz(yaw) p + (x, y, 0)
        const float c = std::cos(map_yaw), s = std::sin(map_yaw);
        for (auto& q : pts) q = Eigen::Vector3f(c * q.x() - s * q.y() + map_x, s * q.x() + c * q.y() + map_y, q.z());
        lio.setMode(mndt::LioMode::Localization);
        lio.reset();
        lio.setPriorMap(pts);
        lio.setInitialGuess(mndt::Vec3(gx, gy, 0), gyaw, !has_gyaw);
        std::printf("prior map: %zu points (yaw %.0f deg, offset %.2f %.2f)\n", n, map_yaw * 180 / M_PI, map_x, map_y);
    }
    std::ofstream traj(prefix + "_traj.csv");
    traj << "t,x,y,z,vx,vy,vz,ms,eff,iter\n";
    std::vector<uint8_t> buf(70000);
    long pkts = 0, scans = 0;
    double t_first = -1;
    while (true) {
        int type = in.get();
        if (type == EOF) break;
        uint8_t l[2];
        if (!in.read(reinterpret_cast<char*>(l), 2)) break;
        int len = l[0] | (l[1] << 8);
        if (!in.read(reinterpret_cast<char*>(buf.data()), len)) break;
        lio.addPacket(type, buf.data(), len);
        ++pkts;
        while (lio.step()) {
            auto st = lio.stats();
            if (st.scans == scans) continue;
            scans = st.scans;
            mndt::Mat3 R; mndt::Vec3 p;
            lio.pose(R, p);
            double t = lio.currentTime();
            if (t_first < 0) t_first = t;
            auto v = lio.velocity();
            traj << (t - t_first) << ',' << p.x() << ',' << p.y() << ',' << p.z() << ','
                 << v.x() << ',' << v.y() << ',' << v.z() << ',' << st.last_ms << ','
                 << st.effective << ',' << st.iterations << '\n';
            if (lio.mode() == mndt::LioMode::Localization && (scans <= 3 || scans % 25 == 0))
                std::printf("t=%7.2f loc=%d match=%.2f score=%.3f it=%d ndt=%.1fms p=(%7.3f %7.3f %7.3f) yaw=%6.1f\n",
                            t - t_first, st.loc_state, st.match, st.ndt_score, st.ndt_iters, st.ndt_ms, p.x(), p.y(),
                            p.z(), std::atan2(R(1, 0), R(0, 0)) * 180 / M_PI);
            else if (scans % 50 == 0)
                std::printf("t=%7.2f p=(%7.3f %7.3f %7.3f) |v|=%5.2f eff=%4d it=%d ds=%4d ms=%5.1f map=%zu\n",
                            t - t_first, p.x(), p.y(), p.z(), v.norm(), st.effective, st.iterations,
                            st.ds_points, st.last_ms, st.map_size);
        }
    }
    auto st = lio.stats();
    mndt::Mat3 R; mndt::Vec3 p;
    lio.pose(R, p);
    std::printf("packets=%ld scans=%ld dropped=%ld degenerate=%ld avg_ms=%.1f\n", pkts, st.scans,
                st.dropped_scans, st.degenerate, st.avg_ms);
    std::printf("final p=(%.3f %.3f %.3f) bg=(%.4f %.4f %.4f) ba=(%.3f %.3f %.3f)\n", p.x(), p.y(), p.z(),
                lio.gyroBias().x(), lio.gyroBias().y(), lio.gyroBias().z(), lio.accBias().x(),
                lio.accBias().y(), lio.accBias().z());
    lio.savePcd(prefix + "_map.pcd");
    return 0;
}
