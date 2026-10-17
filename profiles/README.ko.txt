V1.00 프로필 자료 설명

현재 실제 실행 프로필은 app/.../MeasuredProfiles.java와 integration/control-center/MeasuredProfiles.java에서 정의합니다.
단위 MHz, CPU 저전력4/성능3/최고성능1/GPU 순서:
Default: 자체 요청 해제
Small: 2000 / 3000 / 3100 / 1190
Medium: 1800 / 2700 / 2790 / 1029 (CPU 대응 지원 단계1800/2660/2700)
Large: 900 / 1200 / 1400 / 601

같은 폴더의 MT6897-RG477V.json은 초기 설계 당시 교환 형식 예제를 보존한 레거시 자산입니다. V1.00 화면의 프로필을 정의하지 않으며 현재 측정값으로 사용하거나 별도 앱에 적용하지 마세요. 초기 교환 형식은 ClusterTune의 schemaVersion2 구조를 참고했습니다. 현재 프로필·지원 범위·측정 한계는 README.md와 docs/LIMITATIONS.ko.md를 따릅니다.
