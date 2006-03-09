class_<Defrag, bases<Element>, boost::shared_ptr<Defrag>, boost::noncopyable>
      ("Defrag", init<string>())
  .def("class_name", &Defrag::class_name)
  .def("processing", &Defrag::processing)
  .def("flow_code",  &Defrag::flow_code)
;
